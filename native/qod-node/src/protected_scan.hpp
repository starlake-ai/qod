#pragma once

#include "column_policies.hpp"
#include "row_policies.hpp"

#include "duckdb.hpp"
#include "duckdb/planner/operator/logical_get.hpp"

#include <string>
#include <vector>

namespace qod {

//! A connector's answer to the row policies of one scan.
struct LoweringResult {
	//! True when the connector enforces exactly the rows for which the predicates are TRUE, before any
	//! candidate limit it applies. NULL and evaluation failure must grant nothing.
	bool exact = false;
	//! A stable lowercase code ([a-z_]+), e.g. "policy_unsupported"; anything else reads as that one.
	std::string refusal_code;
};

//! A column a connector scan uses inside its remote request without (necessarily) projecting it.
struct FieldUse {
	enum class Kind { Filter, VectorLineage, Projection };
	std::string column;
	Kind kind;
};

//! Implemented by the bind data (FunctionData) of a connector table function whose get_table_entry
//! returns a registered table. Core hands it the scan's bound row policies at post_bind
//! (ApplyProtectedScans), refuses a bounded scan that cannot lower them exactly, and seals the policy
//! digest; VerifyProtectedScans rechecks the seal before optimization. The connector calls
//! RequireSealed() before any remote I/O.
class ProtectedScan {
public:
	virtual ~ProtectedScan() = default;

	//! True when the connector applies a candidate limit (top-k) remotely: a local filter above it
	//! would change the answer, so exact lowering is mandatory.
	virtual bool IsBounded() const = 0;

	//! `predicates`: the RowPolicyBoundary filter's expressions (ANDed); each is the OR of the matched
	//! policies, each wrapped as TRY(CAST(<policy> AS BOOLEAN)). Column references bind to
	//! get.table_index; their column_index indexes get.GetColumnIds(). Empty when no policy applies
	//! (including privileged principals): the connector still enforces its own mandatory scope.
	virtual LoweringResult AcceptRowPolicies(duckdb::ClientContext &context, const duckdb::LogicalGet &get,
	                                         const std::vector<RowPolicy> &policies,
	                                         const std::vector<duckdb::unique_ptr<duckdb::Expression>> &predicates) = 0;

	//! Every column the remote request reads (filters, vector lineage), projected or not.
	virtual std::vector<FieldUse> FieldUses() const = 0;

	bool IsSealed() const {
		return sealed_;
	}
	const std::string &SealedDigest() const {
		return digest_;
	}
	//! Throws duckdb::PermissionException when core never sealed this scan.
	void RequireSealed() const;

protected:
	//! For FunctionData::Copy: a copy of a sealed scan stays sealed under the same digest.
	void CopySealFrom(const ProtectedScan &other) {
		sealed_ = other.sealed_;
		digest_ = other.digest_;
	}

private:
	friend void SealProtectedScan(ProtectedScan &scan, const std::string &digest);
	bool sealed_ = false;
	std::string digest_;
};

ProtectedScan *AsProtectedScan(duckdb::LogicalGet &get);

//! A table function (lowercase name) that applies a remote candidate limit. A LogicalGet of one whose
//! bind data is not a ProtectedScan is refused. Call before the database serves statements.
void RegisterBoundedFunction(const std::string &lower_name);
bool IsBoundedFunction(const std::string &lower_name);

//! Lowercase hex SHA-256 over the policies' ids and SQL, in order.
std::string PolicyDigest(const std::vector<RowPolicy> &policies);

//! Run after ApplyColumnPolicies and ApplyRowPolicies, for every principal. `column_policies` is
//! empty for privileged principals.
void ApplyProtectedScans(duckdb::ClientContext &context, duckdb::LogicalOperator &plan,
                         const std::vector<ColumnPolicy> &column_policies);

//! Rechecks every protected scan's seal against the policies of the boundary above it (none for a
//! bare scan). Throws duckdb::PermissionException on a mismatch or an unprotected bounded scan.
void VerifyProtectedScans(duckdb::LogicalOperator &plan);

} // namespace qod
