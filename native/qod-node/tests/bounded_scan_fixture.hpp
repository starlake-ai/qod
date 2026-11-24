// Test-only connector: two table functions over a real table entry, standing in for an external
// connector whose scans return a bounded candidate set (top-k). Rows come from a fixed list in
// relevance order, never from the table's storage, so a filter applied only after the limit is
// observable.
#pragma once

#include "duckdb.hpp"
#include "duckdb/catalog/catalog.hpp"
#include "duckdb/catalog/catalog_entry/table_catalog_entry.hpp"
#include "duckdb/common/table_column.hpp"
#include "duckdb/function/table_function.hpp"
#include "duckdb/main/extension.hpp"
#include "duckdb/main/extension/extension_loader.hpp"
#include "duckdb/parser/qualified_name.hpp"
#include "duckdb/planner/expression/bound_cast_expression.hpp"
#include "duckdb/planner/expression/bound_columnref_expression.hpp"
#include "duckdb/planner/expression/bound_comparison_expression.hpp"
#include "duckdb/planner/expression/bound_conjunction_expression.hpp"
#include "duckdb/planner/expression/bound_constant_expression.hpp"
#include "duckdb/planner/expression/bound_function_expression.hpp"
#include "duckdb/planner/expression/bound_operator_expression.hpp"
#include "duckdb/planner/operator/logical_get.hpp"
#include "protected_scan.hpp"

#include <string>
#include <vector>

namespace qod_test {
using namespace duckdb;

struct FixtureRow {
	int32_t id;
	const char *owner;
	const char *secret;
};

inline const std::vector<FixtureRow> &FixtureRows() {
	static const std::vector<FixtureRow> rows {{2, "bob", "b-payload"}, {1, "alice", "12"}, {3, "carol", "c"}};
	return rows;
}

inline column_t ScoreColumn() {
	return VIRTUAL_COLUMN_START + 7;
}

struct FixtureBindData : public FunctionData, public qod::ProtectedScan {
	FixtureBindData(TableCatalogEntry &table_p, idx_t k_p) : table(table_p), k(k_p) {
	}
	TableCatalogEntry &table;
	idx_t k;
	std::vector<std::string> uses;
	//! Set by an exact lowering (Task 2): only rows whose owner equals one of these pass, before k.
	bool lowered = false;
	std::vector<std::string> owners;

	unique_ptr<FunctionData> Copy() const override {
		auto copy = make_uniq<FixtureBindData>(table, k);
		copy->uses = uses;
		copy->lowered = lowered;
		copy->owners = owners;
		copy->CopySealFrom(*this);
		return std::move(copy);
	}
	bool Equals(const FunctionData &other_p) const override {
		auto &other = other_p.Cast<FixtureBindData>();
		return &table == &other.table && k == other.k && uses == other.uses && lowered == other.lowered &&
		       owners == other.owners && SealedDigest() == other.SealedDigest();
	}

	bool IsBounded() const override {
		return true;
	}

	std::vector<qod::FieldUse> FieldUses() const override {
		std::vector<qod::FieldUse> result;
		for (auto &column : uses) {
			result.push_back({column, qod::FieldUse::Kind::Filter});
		}
		return result;
	}

	//! Exact only for TRY(CAST(owner = '<literal>' AS BOOLEAN)) terms joined by OR (the shape
	//! ApplyRowPolicies binds), with owner being table column 1. Anything else is not lowered.
	qod::LoweringResult AcceptRowPolicies(ClientContext &, const LogicalGet &get, const std::vector<qod::RowPolicy> &,
	                                      const std::vector<unique_ptr<Expression>> &predicates) override {
		owners.clear();
		lowered = false;
		if (predicates.empty()) {
			return {true, ""};
		}
		if (predicates.size() != 1) {
			return {false, "policy_unsupported"};
		}
		std::vector<const Expression *> terms;
		auto &root = *predicates[0];
		if (root.GetExpressionClass() == ExpressionClass::BOUND_CONJUNCTION &&
		    root.GetExpressionType() == ExpressionType::CONJUNCTION_OR) {
			for (auto &child : root.Cast<BoundConjunctionExpression>().GetChildren()) {
				terms.push_back(child.get());
			}
		} else {
			terms.push_back(&root);
		}
		for (auto term : terms) {
			// DuckDB 2.0.1: casts and comparisons are function expressions (helper structs).
			if (term->GetExpressionType() == ExpressionType::OPERATOR_TRY) {
				term = term->Cast<BoundOperatorExpression>().GetChildren()[0].get();
			}
			if (BoundCastExpression::IsCast(*term)) {
				term = &BoundCastExpression::Child(term->Cast<BoundFunctionExpression>());
			}
			if (term->GetExpressionType() != ExpressionType::COMPARE_EQUAL || !BoundComparisonExpression::IsComparison(*term)) {
				return {false, "policy_unsupported"};
			}
			auto &cmp = term->Cast<BoundFunctionExpression>();
			auto &left = BoundComparisonExpression::Left(cmp);
			auto *right_expr = &BoundComparisonExpression::Right(cmp);
			// The binder leaves CAST('lit' AS VARCHAR) on the literal; an identity cast is transparent.
			while (BoundCastExpression::IsCast(*right_expr) &&
			       BoundCastExpression::TargetType(right_expr->Cast<BoundFunctionExpression>()) ==
			           BoundCastExpression::SourceType(right_expr->Cast<BoundFunctionExpression>())) {
				right_expr = &BoundCastExpression::Child(right_expr->Cast<BoundFunctionExpression>());
			}
			auto &right = *right_expr;
			if (left.GetExpressionClass() != ExpressionClass::BOUND_COLUMN_REF ||
			    right.GetExpressionClass() != ExpressionClass::BOUND_CONSTANT) {
				return {false, "policy_unsupported"};
			}
			auto &ref = left.Cast<BoundColumnRefExpression>();
			auto slot = ref.Binding().column_index.GetIndex();
			if (slot >= get.GetColumnIds().size() || get.GetColumnIds()[slot].GetPrimaryIndex() != 1) {
				return {false, "policy_unsupported"};
			}
			owners.push_back(right.Cast<BoundConstantExpression>().GetValue().ToString());
		}
		lowered = true;
		return {true, ""};
	}
};

struct FixtureUnprotectedBindData : public FunctionData {
	FixtureUnprotectedBindData(TableCatalogEntry &table_p, idx_t k_p) : table(table_p), k(k_p) {
	}
	TableCatalogEntry &table;
	idx_t k;
	unique_ptr<FunctionData> Copy() const override {
		return make_uniq<FixtureUnprotectedBindData>(table, k);
	}
	bool Equals(const FunctionData &other_p) const override {
		auto &other = other_p.Cast<FixtureUnprotectedBindData>();
		return &table == &other.table && k == other.k;
	}
};

struct FixtureState : public GlobalTableFunctionState {
	vector<column_t> column_ids;
	idx_t next = 0;
	bool done = false;
};

inline TableCatalogEntry &FixtureTable(ClientContext &context, const Value &name) {
	return Catalog::GetEntry<TableCatalogEntry>(context, QualifiedName::Parse(name.ToString()));
}

inline void FixtureColumns(TableCatalogEntry &table, vector<LogicalType> &types, vector<Identifier> &names) {
	for (auto &column : table.GetColumns().Logical()) {
		types.push_back(column.Type());
		names.push_back(column.Name());
	}
}

inline unique_ptr<FunctionData> FixtureBind(ClientContext &context, TableFunctionBindInput &input,
                                            vector<LogicalType> &types, vector<Identifier> &names) {
	auto &table = FixtureTable(context, input.inputs[0]);
	FixtureColumns(table, types, names);
	auto data = make_uniq<FixtureBindData>(table, NumericCast<idx_t>(input.inputs[1].GetValue<int32_t>()));
	for (auto &entry : input.named_parameters) {
		if (entry.first.GetIdentifierName() == "uses") {
			for (auto &value : ListValue::GetChildren(entry.second)) {
				data->uses.push_back(value.ToString());
			}
		}
	}
	return std::move(data);
}

inline unique_ptr<FunctionData> FixtureUnprotectedBind(ClientContext &context, TableFunctionBindInput &input,
                                                       vector<LogicalType> &types, vector<Identifier> &names) {
	auto &table = FixtureTable(context, input.inputs[0]);
	FixtureColumns(table, types, names);
	return make_uniq<FixtureUnprotectedBindData>(table, NumericCast<idx_t>(input.inputs[1].GetValue<int32_t>()));
}

inline optional_ptr<TableCatalogEntry> FixtureTableEntry(optional_ptr<const FunctionData> bind_data) {
	if (auto data = dynamic_cast<const FixtureBindData *>(bind_data.get())) {
		return &data->table;
	}
	return &bind_data->Cast<FixtureUnprotectedBindData>().table;
}

inline virtual_column_map_t FixtureVirtualColumns(ClientContext &, optional_ptr<FunctionData>) {
	virtual_column_map_t result;
	result.insert(make_pair(ScoreColumn(), TableColumn(Identifier("_qod_test_score"), LogicalType::DOUBLE)));
	return result;
}

inline unique_ptr<GlobalTableFunctionState> FixtureInit(ClientContext &, TableFunctionInitInput &input) {
	if (auto data = dynamic_cast<const FixtureBindData *>(input.bind_data.get())) {
		data->RequireSealed();
	}
	auto state = make_uniq<FixtureState>();
	state->column_ids = input.column_ids;
	return std::move(state);
}

inline void FixtureScan(ClientContext &, TableFunctionInput &input, DataChunk &output) {
	auto &state = input.global_state->Cast<FixtureState>();
	if (state.done) {
		return;
	}
	state.done = true;
	idx_t k;
	const FixtureBindData *protected_data = dynamic_cast<const FixtureBindData *>(input.bind_data.get());
	if (protected_data) {
		k = protected_data->k;
	} else {
		k = input.bind_data->Cast<FixtureUnprotectedBindData>().k;
	}
	idx_t out = 0;
	idx_t rank = 0;
	for (auto &row : FixtureRows()) {
		if (out >= k) {
			break;
		}
		//! The score reflects the row's place in the full relevance list, admitted or not.
		++rank;
		if (protected_data && protected_data->lowered) {
			bool admitted = false;
			for (auto &owner : protected_data->owners) {
				admitted |= owner == row.owner;
			}
			if (!admitted) {
				continue;
			}
		}
		for (idx_t c = 0; c < state.column_ids.size(); ++c) {
			auto id = state.column_ids[c];
			Value value;
			if (id == ScoreColumn()) {
				value = Value::DOUBLE(1.0 / double(rank));
			} else if (id == 0) {
				value = Value::INTEGER(row.id);
			} else if (id == 1) {
				value = Value(row.owner);
			} else if (id == 2) {
				value = Value(row.secret);
			} else {
				value = Value();
			}
			output.SetValue(c, out, value);
		}
		++out;
	}
	output.SetCardinality(out);
}

inline TableFunction FixtureFunction(const char *name, table_function_bind_t bind, bool with_uses = false) {
	// DuckDB 2.0.1: named parameters are declared on a FunctionSignature (no named_parameters member).
	FunctionSignature signature;
	signature.AddParameter(LogicalType::VARCHAR);
	signature.AddParameter(LogicalType::INTEGER);
	if (with_uses) {
		signature.AddKeywordOnly(Identifier("uses"), LogicalType::LIST(LogicalType::VARCHAR),
		                         Value::LIST(LogicalType::VARCHAR, vector<Value>()));
	}
	TableFunction function(Identifier(name), std::move(signature), FixtureScan, bind, FixtureInit);
	function.get_table_entry = FixtureTableEntry;
	function.get_virtual_columns = FixtureVirtualColumns;
	function.projection_pushdown = true;
	return function;
}

class BoundedScanFixture : public Extension {
public:
	void Load(ExtensionLoader &loader) override {
		loader.RegisterFunction(FixtureFunction("qod_test_bounded_scan", FixtureBind, true));
		loader.RegisterFunction(FixtureFunction("qod_test_unprotected_bounded", FixtureUnprotectedBind));
		qod::RegisterBoundedFunction("qod_test_unprotected_bounded");
	}
	std::string Name() override {
		return "qod_test_bounded_scan";
	}
};

} // namespace qod_test
