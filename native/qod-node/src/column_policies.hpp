#pragma once

#include "duckdb.hpp"
#include "duckdb/planner/operator/logical_secure_view.hpp"

#include <string>
#include <vector>

namespace qod {

struct ColumnPolicy {
	std::string id;
	std::string catalog;
	std::string schema;
	std::string table;
	std::string column;
	std::string action;
	std::string sql;
	bool operator==(const ColumnPolicy &other) const;
};

bool ColumnPolicyMatches(const ColumnPolicy &policy, const std::string &catalog, const std::string &schema,
                         const std::string &table);

//! A masking boundary is an optimizer boundary, never a definer-rights view.
//! Its projection keeps the caller's original binding indexes; its child scan
//! has a new, private raw binding index.
class ColumnPolicyBoundary : public duckdb::LogicalSecureView {
public:
	ColumnPolicyBoundary(duckdb::unique_ptr<duckdb::LogicalOperator> projection, const duckdb::LogicalOperator *scan,
	                     std::vector<ColumnPolicy> policies);
	const duckdb::LogicalOperator *scan;
	duckdb::TableIndex raw_index;
	duckdb::TableIndex output_index;
	std::vector<ColumnPolicy> policies;
	duckdb::vector<duckdb::ColumnIndex> columns;
	duckdb::vector<duckdb::unique_ptr<duckdb::Expression>> masks;
};

//! Apply before ApplyRowPolicies: the row filter is subsequently installed
//! inside the masking projection, over raw values. Only caller-bound scan
//! columns are exposed by this projection.
void ApplyColumnPolicies(duckdb::ClientContext &context, duckdb::Binder &binder,
                         duckdb::unique_ptr<duckdb::LogicalOperator> &plan, const std::vector<ColumnPolicy> &policies);

//! Recheck after later post-bind callbacks, before optimization. A
//! changed/missing boundary or mask, another policy vector, or a scan whose
//! original column slots changed refuses.
void VerifyColumnPolicies(duckdb::LogicalOperator &plan, const std::vector<ColumnPolicy> &policies);

} // namespace qod
