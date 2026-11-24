// The connector policy obligation (protected_scan.hpp) through the real post_bind and pre_optimize
// hooks, with the bounded_scan_fixture.hpp connector standing in for an external one.
#include "bounded_scan_fixture.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/planner/operator/logical_get.hpp"
#include "duckdb/planner/planner_extension.hpp"
#include "node_hooks.hpp"
#include "row_policies.hpp"
#include "test_handles.hpp"

#include <iostream>

namespace {
using namespace duckdb;
int failures = 0;
int checks = 0;
int64_t now = 1761200000000;
struct Client : ClientContextState {};

std::string Run(Connection &con, const std::string &sql) {
	auto result = con.Query(sql);
	if (result->HasError()) return "ERROR: " + result->GetError();
	std::string out;
	while (auto chunk = result->FetchRaw()) {
		for (idx_t row = 0; row < chunk->size(); ++row) {
			out += (out.empty() ? "" : ",") + chunk->GetValue(0, row).ToString();
		}
	}
	return out;
}

void Expect(const std::string &what, const std::string &got, const std::string &expected) {
	++checks;
	if (got != expected) {
		std::cerr << "FAIL " << what << ": expected " << expected << ", got " << got << std::endl;
		++failures;
	}
}

void Refused(const std::string &what, const std::string &got, const std::string &needle) {
	++checks;
	if (got.rfind("ERROR: ", 0) != 0 || got.find(needle) == std::string::npos) {
		std::cerr << "FAIL " << what << ": expected refusal " << needle << ", got " << got << std::endl;
		++failures;
	}
}

bool unseal = false;
void Unseal(PlannerExtensionInput &input, BoundStatement &statement) {
	if (!unseal || !statement.plan) return;
	std::function<void(LogicalOperator &)> walk = [&](LogicalOperator &op) {
		if (op.type == LogicalOperatorType::LOGICAL_GET) {
			auto &get = op.Cast<LogicalGet>();
			if (auto data = dynamic_cast<qod_test::FixtureBindData *>(get.bind_data.get())) {
				get.bind_data = make_uniq<qod_test::FixtureBindData>(data->table, data->k);
			}
		}
		for (auto &child : op.children) walk(*child);
	};
	walk(*statement.plan);
}
} // namespace

int main() {
	DuckDB db(nullptr);
	db.LoadStaticExtension<qod_test::BoundedScanFixture>();
	auto authz = std::make_shared<qod::NodeAuthz>(qod_test::kNode, qod_test::kKey, [] { return now; });
	qod::RegisterNodeAuthz(*db.instance, authz, [](const qod::DecideCall &) {
		qod::DecideResult result;
		result.status = qod::DecideResult::Status::Allow;
		result.has_verdict = true;
		result.verdict.expires_at = now + 60000;
		return result;
	});
	PlannerExtension later;
	later.post_bind_function = Unseal;
	PlannerExtension::Register(DBConfig::GetConfig(*db.instance), later);
	Connection setup(db);
	Expect("seed", Run(setup, "CREATE TABLE docs(id INTEGER, owner VARCHAR, secret VARCHAR)"), "");
	Connection user(db);
	user.context->registered_state->Insert(qod::kQuackSessionKey, make_shared_ptr<Client>());
	qod_test::MintSpec handle;
	handle.principal_id = "alice";
	handle.exp = now + 60000;
	handle.row_policies = {{"p1", "memory", "main", "docs", "owner = 'alice'"}};
	auto install = [&] {
		Expect("install", Run(user, qod_test::Prelude(qod_test::MintWith(handle))), "true");
	};
	install();

	// Spike evidence (DuckDB 2.0.1): the existing RLS pass matches a connector scan by its table entry.
	Expect("connector scan is matched by its table entry",
	       Run(user, "SELECT id FROM qod_test_bounded_scan('memory.main.docs', 3)"), "1");
	Expect("virtual column survives the boundary",
	       Run(user, "SELECT _qod_test_score FROM qod_test_bounded_scan('memory.main.docs', 3)"), "0.5");
	Expect("policy enforced before the candidate limit",
	       Run(user, "SELECT id FROM qod_test_bounded_scan('memory.main.docs', 1)"), "1");
	handle.row_policies.push_back({"p2", "memory", "main", "docs", "owner = 'carol'"});
	++handle.policy_version;
	install();
	Expect("OR of policies lowered", Run(user, "SELECT id FROM qod_test_bounded_scan('memory.main.docs', 2)"), "1,3");
	handle.row_policies = {{"p3", "memory", "main", "docs", "id = 2"}};
	++handle.policy_version;
	install();
	Refused("inexact lowering refuses before any fetch",
	        Run(user, "SELECT id FROM qod_test_bounded_scan('memory.main.docs', 1)"), "policy_unsupported");
	Refused("bounded function without the obligation",
	        Run(user, "SELECT id FROM qod_test_unprotected_bounded('memory.main.docs', 1)"), "policy obligation");
	handle.row_policies = {{"p1", "memory", "main", "docs", "owner = 'alice'"}};
	++handle.policy_version;
	install();
	unseal = true;
	Refused("scan replaced after sealing", Run(user, "SELECT id FROM qod_test_bounded_scan('memory.main.docs', 1)"),
	        "could not be verified");
	unseal = false;
	Refused("internal connection never sealed", Run(setup, "SELECT id FROM qod_test_bounded_scan('memory.main.docs', 1)"),
	        "without a sealed policy obligation");
	Expect("prepare", Run(user, "PREPARE top AS SELECT id FROM qod_test_bounded_scan('memory.main.docs', 1)"), "");
	Expect("execute", Run(user, "EXECUTE top"), "1");
	handle.row_policies[0].sql = "owner = 'bob'";
	++handle.policy_version;
	install();
	Expect("prepared scan reseals after a policy change", Run(user, "EXECUTE top"), "2");
	// A handle refresh cannot change a connection's principal (RefreshRefusal): use a second one.
	Connection root(db);
	root.context->registered_state->Insert(qod::kQuackSessionKey, make_shared_ptr<Client>());
	qod_test::MintSpec admin;
	admin.principal_id = "root";
	admin.exp = now + 60000;
	admin.superuser = true;
	Expect("install superuser", Run(root, qod_test::Prelude(qod_test::MintWith(admin))), "true");
	Expect("superuser scan sealed with no row policy",
	       Run(root, "SELECT id FROM qod_test_bounded_scan('memory.main.docs', 1)"), "2");

	std::cout << checks << " checks, " << failures << " failures" << std::endl;
	return failures == 0 ? 0 : 1;
}
