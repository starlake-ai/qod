#include "protected_scan.hpp"

#include "crypto.hpp"
#include "duckdb/catalog/catalog.hpp"
#include "duckdb/catalog/catalog_entry/table_catalog_entry.hpp"
#include "duckdb/common/string_util.hpp"

#include <mutex>
#include <set>

namespace qod {
using namespace duckdb;

namespace {
constexpr const char *kUnprotected = "QoD: a bounded connector scan without a policy obligation is not supported";
constexpr const char *kUnsealed = "QoD: connector scan ran without a sealed policy obligation";
constexpr const char *kMismatch = "QoD: connector scan policy obligation could not be verified";
constexpr const char *kFieldDenied = "QoD: field_denied: a connector scan uses a column that has a column policy";

std::mutex &RegistryMutex() {
	static std::mutex mutex;
	return mutex;
}

std::set<std::string> &Registry() {
	static std::set<std::string> names;
	return names;
}

std::string FunctionName(LogicalGet &get) {
	return StringUtil::Lower(get.function.GetQualifiedName().Name().GetIdentifierName());
}

std::string SafeCode(const std::string &code) {
	if (code.empty()) {
		return "policy_unsupported";
	}
	for (auto c : code) {
		if (!(c == '_' || (c >= 'a' && c <= 'z'))) {
			return "policy_unsupported";
		}
	}
	return code;
}

void Handle(ClientContext &context, LogicalGet &get, const std::vector<RowPolicy> &policies,
            const std::vector<unique_ptr<Expression>> &predicates, const std::vector<ColumnPolicy> &columns) {
	auto scan = AsProtectedScan(get);
	if (!scan) {
		if (IsBoundedFunction(FunctionName(get))) {
			throw PermissionException(kUnprotected);
		}
		return;
	}
	auto table = get.GetTable();
	if (!table) {
		throw PermissionException(kUnprotected);
	}
	auto catalog = table->ParentCatalog().GetName().GetIdentifierName();
	auto schema = table->ParentSchema().name.GetIdentifierName();
	auto name = table->name.GetIdentifierName();
	for (auto &use : scan->FieldUses()) {
		if (use.kind == FieldUse::Kind::Projection) {
			continue;
		}
		for (auto &policy : columns) {
			if (ColumnPolicyMatches(policy, catalog, schema, name) && StringUtil::CIEquals(policy.column, use.column)) {
				throw PermissionException(kFieldDenied);
			}
		}
	}
	auto result = scan->AcceptRowPolicies(context, get, policies, predicates);
	if (scan->IsBounded() && !result.exact) {
		throw PermissionException("QoD: " + SafeCode(result.refusal_code) +
		                          ": a row policy cannot be enforced before the connector's candidate limit");
	}
	SealProtectedScan(*scan, PolicyDigest(policies));
}

void Walk(ClientContext &context, LogicalOperator &op, const std::vector<ColumnPolicy> &columns,
          std::set<const LogicalOperator *> &done) {
	if (auto boundary = dynamic_cast<RowPolicyBoundary *>(&op)) {
		auto &get = const_cast<LogicalOperator &>(*boundary->scan).Cast<LogicalGet>();
		Handle(context, get, boundary->policies, boundary->predicates, columns);
		done.insert(boundary->scan);
	}
	if (op.type == LogicalOperatorType::LOGICAL_GET && !done.count(&op)) {
		Handle(context, op.Cast<LogicalGet>(), {}, {}, columns);
	}
	for (auto &child : op.children) {
		Walk(context, *child, columns, done);
	}
}

void Check(LogicalGet &get, const std::vector<RowPolicy> &policies) {
	auto scan = AsProtectedScan(get);
	if (!scan) {
		if (IsBoundedFunction(FunctionName(get))) {
			throw PermissionException(kUnprotected);
		}
		return;
	}
	if (!scan->IsSealed() || scan->SealedDigest() != PolicyDigest(policies)) {
		throw PermissionException(kMismatch);
	}
}

void VerifyWalk(LogicalOperator &op, std::set<const LogicalOperator *> &done) {
	if (auto boundary = dynamic_cast<RowPolicyBoundary *>(&op)) {
		Check(const_cast<LogicalOperator &>(*boundary->scan).Cast<LogicalGet>(), boundary->policies);
		done.insert(boundary->scan);
	}
	if (op.type == LogicalOperatorType::LOGICAL_GET && !done.count(&op)) {
		Check(op.Cast<LogicalGet>(), {});
	}
	for (auto &child : op.children) {
		VerifyWalk(*child, done);
	}
}
} // namespace

void SealProtectedScan(ProtectedScan &scan, const std::string &digest) {
	scan.sealed_ = true;
	scan.digest_ = digest;
}

void ProtectedScan::RequireSealed() const {
	if (!sealed_) {
		throw PermissionException(kUnsealed);
	}
}

ProtectedScan *AsProtectedScan(LogicalGet &get) {
	return dynamic_cast<ProtectedScan *>(get.bind_data.get());
}

void RegisterBoundedFunction(const std::string &lower_name) {
	std::lock_guard<std::mutex> lock(RegistryMutex());
	Registry().insert(lower_name);
}

bool IsBoundedFunction(const std::string &lower_name) {
	std::lock_guard<std::mutex> lock(RegistryMutex());
	return Registry().count(lower_name) != 0;
}

std::string PolicyDigest(const std::vector<RowPolicy> &policies) {
	std::string canonical;
	for (auto &p : policies) {
		canonical += p.id;
		canonical += '\x1f';
		canonical += p.sql;
		canonical += '\x1e';
	}
	return Hex(Sha256(canonical));
}

void ApplyProtectedScans(ClientContext &context, LogicalOperator &plan, const std::vector<ColumnPolicy> &column_policies) {
	std::set<const LogicalOperator *> done;
	Walk(context, plan, column_policies, done);
}

void VerifyProtectedScans(LogicalOperator &plan) {
	std::set<const LogicalOperator *> done;
	VerifyWalk(plan, done);
}

} // namespace qod
