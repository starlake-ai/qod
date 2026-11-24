// The connector policy obligation (protected_scan.hpp) through the real post_bind and pre_optimize
// hooks, with the bounded_scan_fixture.hpp connector standing in for an external one.
#include "bounded_scan_fixture.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/main/config.hpp"
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
	// The gap: the local boundary filters after the connector's candidate limit, so the best
	// authorized row is lost. Task 2 changes this expectation to "1".
	Expect("top-k gap before the obligation", Run(user, "SELECT id FROM qod_test_bounded_scan('memory.main.docs', 1)"),
	       "");

	std::cout << checks << " checks, " << failures << " failures" << std::endl;
	return failures == 0 ? 0 : 1;
}
