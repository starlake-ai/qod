#pragma once

#include "duckdb.hpp"
#include "duckdb/planner/operator/logical_secure_view.hpp"

#include <string>
#include <vector>

namespace qod {

//! Identity substitution is performed by the manager, before this is signed into a handle.
struct RowPolicy {
	std::string id;
	std::string catalog;
	std::string schema;
	std::string table;
	std::string sql;

	bool operator==(const RowPolicy &other) const;
};

bool RowPolicyMatches(const RowPolicy &policy, const std::string &catalog, const std::string &schema,
                      const std::string &table);

//! A node-created optimization boundary, not a catalog view and never a definer-rights boundary.
//! SQL cannot construct one. DuckDB's secure-view optimizer protects caller expressions from being
//! evaluated on rows removed by its child filter, including when filter pushdown is enabled.
class RowPolicyBoundary : public duckdb::LogicalSecureView {
public:
	RowPolicyBoundary(duckdb::unique_ptr<duckdb::LogicalOperator> child,
	                  std::vector<RowPolicy> policies);
	std::vector<RowPolicy> policies;
	std::vector<duckdb::unique_ptr<duckdb::Expression>> predicates;
	const duckdb::LogicalOperator *scan;
};

//! Applies every matching predicate (permissive OR) to every bound base-table scan. Unsupported
//! protected writes and binding failures refuse; policies never fall back to an unfiltered scan.
void ApplyRowPolicies(duckdb::ClientContext &context, duckdb::unique_ptr<duckdb::LogicalOperator> &plan,
                      const std::vector<RowPolicy> &policies);

//! Rechecks placement after other post-bind extensions, before optimization. Rejects an unguarded
//! protected scan, a changed filter or a boundary from another policy set.
void VerifyRowPolicies(duckdb::LogicalOperator &plan, const std::vector<RowPolicy> &policies);

} // namespace qod
