#include "row_policies.hpp"

#include "function_classes.hpp"
#include "duckdb/catalog/catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry/table_catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry/type_catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry/schema_catalog_entry.hpp"
#include "duckdb/common/string_util.hpp"
#include "duckdb/parser/parser.hpp"
#include "duckdb/parser/expression/columnref_expression.hpp"
#include "duckdb/parser/expression/conjunction_expression.hpp"
#include "duckdb/parser/expression/cast_expression.hpp"
#include "duckdb/parser/expression/operator_expression.hpp"
#include "duckdb/parser/parsed_expression_iterator.hpp"
#include "duckdb/planner/binder.hpp"
#include "duckdb/planner/expression/bound_columnref_expression.hpp"
#include "duckdb/planner/expression_binder/where_binder.hpp"
#include "duckdb/planner/expression_iterator.hpp"
#include "duckdb/planner/operator/logical_filter.hpp"
#include "duckdb/planner/operator/logical_get.hpp"
#include "duckdb/main/client_config.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/main/settings.hpp"

namespace qod {
using namespace duckdb;

bool RowPolicy::operator==(const RowPolicy &p) const {
	return id == p.id && catalog == p.catalog && schema == p.schema && table == p.table && sql == p.sql;
}

bool RowPolicyMatches(const RowPolicy &p, const std::string &catalog, const std::string &schema,
                      const std::string &table) {
	auto matches = [](const std::string &a, const std::string &b) {
		return a == "*" || StringUtil::CIEquals(a, b);
	};
	return matches(p.catalog, catalog) && matches(p.schema, schema) && matches(p.table, table);
}

namespace {
constexpr const char *kBindingFailure = "QoD: row policy could not be bound to its table";
constexpr const char *kPlacementFailure = "QoD: row policy filter placement could not be verified";

std::vector<RowPolicy> Matching(LogicalGet &get, const std::vector<RowPolicy> &policies) {
	std::vector<RowPolicy> result;
	auto table = get.GetTable();
	if (!table) {
		return result;
	}
	for (auto &p : policies) {
		if (RowPolicyMatches(p, table->ParentCatalog().GetName().GetIdentifierName(),
		                     table->ParentSchema().name.GetIdentifierName(), table->name.GetIdentifierName())) {
			result.push_back(p);
		}
	}
	return result;
}

//! No parent binder: a missing column can never bind to a caller's outer query. Strip only the
//! protected table's own qualifiers; struct fields and lambda references keep DuckDB's semantics.
void PrepareExpression(ParsedExpression &expr, TableCatalogEntry &table) {
	if (expr.GetExpressionClass() == ExpressionClass::SUBQUERY ||
	    expr.GetExpressionClass() == ExpressionClass::PARAMETER ||
	    expr.GetExpressionClass() == ExpressionClass::LAMBDA ||
	    expr.GetExpressionClass() == ExpressionClass::WINDOW) {
		throw PermissionException(kBindingFailure);
	}
	if (expr.GetExpressionClass() == ExpressionClass::COLUMN_REF) {
		auto &names = expr.Cast<ColumnRefExpression>().ColumnNamesMutable();
		vector<Identifier> own {table.ParentCatalog().GetName(), table.ParentSchema().name, table.name};
		for (idx_t count = 3; count > 0; --count) {
			if (names.size() <= count) {
				continue;
			}
			bool match = true;
			for (idx_t i = 0; i < count; ++i) {
				match &= names[i] == own[3 - count + i];
			}
			if (match) {
				names.erase(names.begin(), names.begin() + count);
				break;
			}
		}
	}
	ParsedExpressionIterator::EnumerateChildren(expr, [&](ParsedExpression &child) {
		PrepareExpression(child, table);
	});
}

unique_ptr<Expression> BindPredicate(ClientContext &context, LogicalGet &get,
                                     const std::vector<RowPolicy> &policies) {
	try {
		if (!Settings::Get<DefaultCollationSetting>(context).empty()) {
			throw PermissionException(kBindingFailure);
		}
		for (auto name : {"default_collation", "integer_division", "ieee_floating_point_ops", "lambda_syntax"}) {
			auto option = DBConfig::GetOptionByName(Identifier(name));
			if (option && option->setting_idx.IsValid() &&
			    ClientConfig::GetConfig(context).user_settings.IsSet(option->setting_idx.GetIndex())) {
				throw PermissionException(kBindingFailure);
			}
		}
		auto parser = Parser::GetBuiltinParser();
		vector<unique_ptr<ParsedExpression>> each;
		for (auto &p : policies) {
			auto one = parser.ParseSingleExpression(p.sql);
			PrepareExpression(*one, *get.GetTable());
			each.push_back(make_uniq<OperatorExpression>(ExpressionType::OPERATOR_TRY,
			    make_uniq<CastExpression>(LogicalType::BOOLEAN, std::move(one))));
		}
		// Errors in a policy must not quote a hidden value. Failed conversions become NULL and
		// therefore reject that row. Volatile functions in TRY are rejected by DuckDB's binder.
		unique_ptr<ParsedExpression> parsed = each.size() == 1 ? std::move(each[0]) :
		    make_uniq<ConjunctionExpression>(ExpressionType::CONJUNCTION_OR, std::move(each));
		auto binder = Binder::CreateBinder(context);
		binder->bind_context.AddGenericBinding(get.table_index, get.GetTable()->name, get.names,
		                                       get.returned_types);
		binder->SetCatalogLookupCallback([](CatalogEntry &entry) {
			if (entry.type == CatalogType::TYPE_ENTRY && entry.Cast<TypeCatalogEntry>().internal) {
				return;
			}
			auto name = StringUtil::Lower(entry.name.GetIdentifierName());
			if (entry.ParentCatalog().GetName() != Identifier("system")) {
				throw PermissionException(kBindingFailure);
			}
			if (entry.type == CatalogType::SCALAR_FUNCTION_ENTRY || entry.type == CatalogType::MACRO_ENTRY) {
				auto cls = FunctionClassOf(name, entry.type == CatalogType::MACRO_ENTRY ? "macro" : "scalar");
				if (!cls || *cls != FunctionClass::Allowed || name == "getvariable" || name == "current_setting") {
					throw PermissionException(kBindingFailure);
				}
			} else if (entry.type != CatalogType::TYPE_ENTRY && entry.type != CatalogType::COLLATION_ENTRY) {
				throw PermissionException(kBindingFailure);
			}
		});
		WhereBinder expression_binder(*binder, context);
		auto result = expression_binder.Bind(parsed);
		std::function<void(Expression &)> remap = [&](Expression &expr) {
			if (expr.GetExpressionClass() == ExpressionClass::BOUND_COLUMN_REF) {
				auto &column = expr.Cast<BoundColumnRefExpression>();
				if (column.Depth() != 0 || column.Binding().table_index != get.table_index) {
					throw PermissionException(kBindingFailure);
				}
				column.BindingMutable().column_index = get.AddColumnId(column.Binding().column_index.GetIndex());
			}
			ExpressionIterator::EnumerateChildren(expr, [&](Expression &child) { remap(child); });
		};
		remap(*result);
		return result;
	} catch (std::exception &) {
		// Never return a binder excerpt, policy text, column value or identity literal.
		throw PermissionException(kBindingFailure);
	}
}

void Apply(ClientContext &context, unique_ptr<LogicalOperator> &op, const std::vector<RowPolicy> &policies) {
	if (dynamic_cast<RowPolicyBoundary *>(op.get())) {
		return;
	}
	for (auto &child : op->children) {
		Apply(context, child, policies);
	}
	if (op->type != LogicalOperatorType::LOGICAL_GET) {
		return;
	}
	auto &get = op->Cast<LogicalGet>();
	auto matched = Matching(get, policies);
	if (matched.empty()) {
		return;
	}
	auto filter = make_uniq<LogicalFilter>(BindPredicate(context, get, matched));
	filter->children.push_back(std::move(op));
	op = make_uniq<RowPolicyBoundary>(std::move(filter), std::move(matched));
}

void Verify(LogicalOperator &op, const std::vector<RowPolicy> &policies) {
	if (auto boundary = dynamic_cast<RowPolicyBoundary *>(&op)) {
		if (boundary->children.size() != 1 || boundary->children[0]->type != LogicalOperatorType::LOGICAL_FILTER) {
			throw PermissionException(kPlacementFailure);
		}
		auto &filter = *boundary->children[0];
		if (filter.children.size() != 1 || filter.children[0].get() != boundary->scan ||
		    filter.children[0]->type != LogicalOperatorType::LOGICAL_GET ||
		    Matching(filter.children[0]->Cast<LogicalGet>(), policies) != boundary->policies ||
		    filter.expressions.size() != boundary->predicates.size()) {
			throw PermissionException(kPlacementFailure);
		}
		for (idx_t i = 0; i < filter.expressions.size(); ++i) {
			if (!filter.expressions[i]->Equals(*boundary->predicates[i])) {
				throw PermissionException(kPlacementFailure);
			}
		}
		return;
	}
	if (op.type == LogicalOperatorType::LOGICAL_GET && !Matching(op.Cast<LogicalGet>(), policies).empty()) {
		throw PermissionException(kPlacementFailure);
	}
	for (auto &child : op.children) {
		Verify(*child, policies);
	}
}
} // namespace

RowPolicyBoundary::RowPolicyBoundary(unique_ptr<LogicalOperator> child, std::vector<RowPolicy> policies_p)
    : LogicalSecureView("QoD row policy", std::move(child)), policies(std::move(policies_p)) {
	auto &filter = *children[0];
	scan = filter.children[0].get();
	for (auto &expr : filter.expressions) {
		predicates.push_back(expr->Copy());
	}
}

void ApplyRowPolicies(ClientContext &context, unique_ptr<LogicalOperator> &plan,
                      const std::vector<RowPolicy> &policies) {
	if (!policies.empty()) {
		Apply(context, plan, policies);
		Verify(*plan, policies);
	}
}

void VerifyRowPolicies(LogicalOperator &plan, const std::vector<RowPolicy> &policies) {
	if (!policies.empty()) {
		Verify(plan, policies);
	}
}
} // namespace qod
