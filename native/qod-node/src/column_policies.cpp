#include "column_policies.hpp"

#include "duckdb/catalog/catalog.hpp"
#include "duckdb/catalog/catalog_entry/table_catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry/type_catalog_entry.hpp"
#include "duckdb/common/string_util.hpp"
#include "duckdb/main/client_config.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/main/settings.hpp"
#include "duckdb/parser/expression/cast_expression.hpp"
#include "duckdb/parser/expression/columnref_expression.hpp"
#include "duckdb/parser/expression/constant_expression.hpp"
#include "duckdb/parser/expression/operator_expression.hpp"
#include "duckdb/parser/parsed_expression_iterator.hpp"
#include "duckdb/parser/parser.hpp"
#include "duckdb/planner/binder.hpp"
#include "duckdb/planner/expression/bound_columnref_expression.hpp"
#include "duckdb/planner/expression_binder/where_binder.hpp"
#include "duckdb/planner/expression_iterator.hpp"
#include "duckdb/planner/operator/logical_get.hpp"
#include "duckdb/planner/operator/logical_projection.hpp"
#include "function_classes.hpp"
#include "row_policies.hpp"

namespace qod {
using namespace duckdb;

bool ColumnPolicy::operator==(const ColumnPolicy &p) const {
	return id == p.id && catalog == p.catalog && schema == p.schema && table == p.table && column == p.column &&
	       action == p.action && sql == p.sql;
}

bool ColumnPolicyMatches(const ColumnPolicy &p, const std::string &catalog, const std::string &schema,
                         const std::string &table) {
	auto matches = [](const std::string &a, const std::string &b) {
		return a == "*" || StringUtil::CIEquals(a, b);
	};
	return matches(p.catalog, catalog) && matches(p.schema, schema) && matches(p.table, table);
}

namespace {
constexpr const char *kBindingFailure = "QoD: column mask cannot be applied safely with its original column type";
constexpr const char *kPlacementFailure = "QoD: column policy placement could not be verified";
constexpr const char *kDenied = "QoD: access to a column is denied by a column policy";
constexpr const char *kConflict = "QoD: conflicting column masks";

std::vector<ColumnPolicy> Matching(LogicalGet &get, const std::vector<ColumnPolicy> &policies) {
	std::vector<ColumnPolicy> result;
	auto table = get.GetTable();
	if (!table) {
		return result;
	}
	for (auto &p : policies) {
		if (ColumnPolicyMatches(p, table->ParentCatalog().GetName().GetIdentifierName(),
		                        table->ParentSchema().name.GetIdentifierName(), table->name.GetIdentifierName())) {
			result.push_back(p);
		}
	}
	return result;
}

//! Coverage is checked by full source coordinates before precedence is
//! evaluated. A table wildcard keeps its original specificity, even when
//! another schema holds a table of this name.
const ColumnPolicy *Chosen(const std::vector<ColumnPolicy> &policies, const std::string &column) {
	std::vector<const ColumnPolicy *> matches;
	bool named = false;
	for (auto &p : policies) {
		if (!StringUtil::CIEquals(p.column, column)) {
			continue;
		}
		if (p.action == "deny") {
			throw PermissionException(kDenied);
		}
		named |= p.table != "*";
		matches.push_back(&p);
	}
	const ColumnPolicy *chosen = nullptr;
	for (auto p : matches) {
		if (named && p->table == "*") {
			continue;
		}
		if (chosen && chosen->sql != p->sql) {
			throw PermissionException(kConflict);
		}
		chosen = p;
	}
	return chosen;
}

void PrepareMask(ParsedExpression &expr, const ColumnPolicy &policy) {
	if (expr.GetExpressionClass() == ExpressionClass::SUBQUERY ||
	    expr.GetExpressionClass() == ExpressionClass::PARAMETER ||
	    expr.GetExpressionClass() == ExpressionClass::LAMBDA || expr.GetExpressionClass() == ExpressionClass::WINDOW) {
		throw PermissionException(kBindingFailure);
	}
	if (expr.GetExpressionClass() == ExpressionClass::COLUMN_REF) {
		auto &names = expr.Cast<ColumnRefExpression>().ColumnNames();
		if (names.size() != 1 || !StringUtil::CIEquals(names[0].GetIdentifierName(), policy.column)) {
			throw PermissionException(kBindingFailure);
		}
	}
	ParsedExpressionIterator::EnumerateChildren(expr, [&](ParsedExpression &child) { PrepareMask(child, policy); });
}

unique_ptr<Expression> BindMask(ClientContext &context, TableIndex raw_index, ProjectionIndex position,
                                const LogicalType &type, const ColumnPolicy &policy) {
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
		auto parsed = Parser::GetBuiltinParser().ParseSingleExpression(policy.sql);
		PrepareMask(*parsed, policy);
		// NULL has no source-dependent conversion. Other masks must bind to the
		// exact original type; silently casting a different mask type would change
		// policy semantics.
		if (parsed->GetExpressionClass() == ExpressionClass::CONSTANT &&
		    parsed->Cast<ConstantExpression>().GetLiteral().IsNull()) {
			parsed = make_uniq<CastExpression>(type, std::move(parsed));
		}
		parsed = make_uniq<OperatorExpression>(ExpressionType::OPERATOR_TRY, std::move(parsed));
		auto binder = Binder::CreateBinder(context);
		binder->bind_context.AddGenericBinding(raw_index, Identifier("__qod_column"), {Identifier(policy.column)},
		                                       {type});
		binder->SetCatalogLookupCallback([](CatalogEntry &entry) {
			if (entry.type == CatalogType::TYPE_ENTRY && entry.Cast<TypeCatalogEntry>().internal) {
				return;
			}
			auto name = StringUtil::Lower(entry.name.GetIdentifierName());
			if (!entry.ParentCatalog().IsSystemCatalog()) {
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
		expression_binder.target_type = LogicalType::INVALID;
		auto result = expression_binder.Bind(parsed);
		if (result->GetReturnType() != type) {
			throw PermissionException(kBindingFailure);
		}
		std::function<void(Expression &)> remap = [&](Expression &expr) {
			if (expr.GetExpressionClass() == ExpressionClass::BOUND_COLUMN_REF) {
				auto &ref = expr.Cast<BoundColumnRefExpression>();
				if (ref.Depth() != 0 || ref.Binding().table_index != raw_index ||
				    ref.Binding().column_index.GetIndex() != 0) {
					throw PermissionException(kBindingFailure);
				}
				ref.BindingMutable().column_index = position;
			}
			ExpressionIterator::EnumerateChildren(expr, [&](Expression &child) { remap(child); });
		};
		remap(*result);
		return result;
	} catch (std::exception &) {
		// Never return policy text, binder excerpts or original values to a reader.
		throw PermissionException(kBindingFailure);
	}
}

void Apply(ClientContext &context, Binder &binder, unique_ptr<LogicalOperator> &op,
           const std::vector<ColumnPolicy> &policies) {
	if (dynamic_cast<ColumnPolicyBoundary *>(op.get())) {
		return;
	}
	for (auto &child : op->children) {
		Apply(context, binder, child, policies);
	}
	if (op->type != LogicalOperatorType::LOGICAL_GET) {
		return;
	}
	auto &get = op->Cast<LogicalGet>();
	auto matched = Matching(get, policies);
	if (matched.empty()) {
		return;
	}
	for (auto &policy : matched) {
		auto name = Identifier(policy.column);
		if (get.GetTable()->ColumnExists(name) && get.GetTable()->GetColumn(name).Generated()) {
			throw PermissionException(
			    "QoD: native CLS does not support policies on generated columns; protect their source columns instead");
		}
	}
	if (!get.projection_ids.empty() || !get.projected_input.empty() || !get.children.empty()) {
		throw PermissionException(kBindingFailure);
	}
	auto original_index = get.table_index;
	auto raw_index = binder.GenerateTableIndex();
	vector<unique_ptr<Expression>> expressions;
	for (idx_t i = 0; i < get.GetColumnIds().size(); ++i) {
		auto &id = get.GetColumnIds()[i];
		if (id.HasChildren() || id.IsPushdownExtract()) {
			throw PermissionException(kBindingFailure);
		}
		auto &type = get.GetColumnType(id);
		auto policy = Chosen(matched, get.GetColumnName(id).GetIdentifierName());
		if (policy) {
			expressions.push_back(BindMask(context, raw_index, ProjectionIndex(i), type, *policy));
		} else {
			expressions.push_back(
			    make_uniq<BoundColumnRefExpression>(type, ColumnBinding(raw_index, ProjectionIndex(i))));
		}
	}
	get.table_index = raw_index;
	auto scan = op.get();
	auto projection = make_uniq<LogicalProjection>(original_index, std::move(expressions));
	projection->children.push_back(std::move(op));
	op = make_uniq<ColumnPolicyBoundary>(std::move(projection), scan, std::move(matched));
}

void Verify(LogicalOperator &op, const std::vector<ColumnPolicy> &policies) {
	if (auto boundary = dynamic_cast<ColumnPolicyBoundary *>(&op)) {
		if (boundary->children.size() != 1 || boundary->children[0]->type != LogicalOperatorType::LOGICAL_PROJECTION) {
			throw PermissionException(kPlacementFailure);
		}
		auto &projection = *boundary->children[0];
		if (projection.children.size() != 1 || projection.expressions.size() != boundary->masks.size() ||
		    projection.Cast<LogicalProjection>().table_index != boundary->output_index || boundary->has_source ||
		    !boundary->output_bindings.empty() || !boundary->output_expressions.empty() ||
		    !boundary->source_filters.empty()) {
			throw PermissionException(kPlacementFailure);
		}
		auto child = projection.children[0].get();
		if (auto rows = dynamic_cast<RowPolicyBoundary *>(child)) {
			if (rows->children.size() != 1 || rows->children[0]->children.size() != 1) {
				throw PermissionException(kPlacementFailure);
			}
			child = rows->children[0]->children[0].get();
		}
		if (child != boundary->scan || child->type != LogicalOperatorType::LOGICAL_GET) {
			throw PermissionException(kPlacementFailure);
		}
		auto &get = child->Cast<LogicalGet>();
		if (Matching(get, policies) != boundary->policies || get.GetColumnIds().size() < boundary->columns.size() ||
		    get.table_index != boundary->raw_index || !get.projection_ids.empty() || !get.projected_input.empty() ||
		    !get.children.empty()) {
			throw PermissionException(kPlacementFailure);
		}
		for (idx_t i = 0; i < boundary->columns.size(); ++i) {
			if (get.GetColumnIds()[i] != boundary->columns[i] ||
			    !projection.expressions[i]->Equals(*boundary->masks[i])) {
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

ColumnPolicyBoundary::ColumnPolicyBoundary(unique_ptr<LogicalOperator> projection, const LogicalOperator *scan_p,
                                           std::vector<ColumnPolicy> policies_p)
    : LogicalSecureView("QoD column policy", std::move(projection)), scan(scan_p), policies(std::move(policies_p)),
      raw_index(scan->Cast<LogicalGet>().table_index), output_index(children[0]->Cast<LogicalProjection>().table_index),
      columns(scan->Cast<LogicalGet>().GetColumnIds()) {
	for (auto &expression : children[0]->expressions) {
		masks.push_back(expression->Copy());
	}
}

void ApplyColumnPolicies(ClientContext &context, Binder &binder, unique_ptr<LogicalOperator> &plan,
                         const std::vector<ColumnPolicy> &policies) {
	if (!policies.empty()) {
		Apply(context, binder, plan, policies);
		Verify(*plan, policies);
	}
}

void VerifyColumnPolicies(LogicalOperator &plan, const std::vector<ColumnPolicy> &policies) {
	if (!policies.empty()) {
		Verify(plan, policies);
	}
}
} // namespace qod
