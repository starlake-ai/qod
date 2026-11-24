#include "node_hooks.hpp"
#include "test_handles.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/planner/planner_extension.hpp"
#include "duckdb/planner/operator/logical_filter.hpp"
#include "row_policies.hpp"

#include <iostream>
#include <filesystem>

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

bool tamper = false;
void Tamper(PlannerExtensionInput &, BoundStatement &statement) {
	if (!tamper || !statement.plan) return;
	std::function<void(unique_ptr<LogicalOperator> &)> walk = [&](unique_ptr<LogicalOperator> &op) {
		if (dynamic_cast<qod::RowPolicyBoundary *>(op.get())) {
			op = std::move(op->children[0]->children[0]);
			return;
		}
		for (auto &child : op->children) walk(child);
	};
	walk(statement.plan);
}
}

int main() {
	DuckDB db(nullptr);
	auto authz = std::make_shared<qod::NodeAuthz>(qod_test::kNode, qod_test::kKey, [] { return now; });
	qod::RegisterNodeAuthz(*db.instance, authz, [](const qod::DecideCall &) {
		qod::DecideResult result;
		result.status = qod::DecideResult::Status::Allow;
		result.has_verdict = true;
		result.verdict.expires_at = now + 60000;
		return result;
	});
	PlannerExtension later;
	later.post_bind_function = Tamper;
	PlannerExtension::Register(DBConfig::GetConfig(*db.instance), later);
	Connection setup(db);
	Expect("seed", Run(setup, "CREATE TABLE docs(id INTEGER, owner VARCHAR, secret VARCHAR); "
	       "INSERT INTO docs VALUES (1,'alice','12'),(2,'bob','hidden-payload'); "
	       "CREATE VIEW v AS SELECT * FROM docs; CREATE VIEW v2 AS SELECT id FROM v"), "");
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
	Expect("direct scan", Run(user, "SELECT id FROM docs"), "1");
	Expect("count needs policy column", Run(user, "SELECT count(*) FROM docs"), "1");
	Expect("view", Run(user, "SELECT id FROM v"), "1");
	Expect("nested view", Run(user, "SELECT id FROM v2"), "1");
	Expect("CTE", Run(user, "WITH x AS MATERIALIZED (SELECT * FROM docs) SELECT id FROM x"), "1");
	Expect("self join", Run(user, "SELECT count(*) FROM docs a JOIN docs b ON true"), "1");
	Expect("outer join", Run(user, "SELECT count(b.id) FROM (VALUES (1),(2)) a(id) LEFT JOIN docs b USING(id)"), "1");
	Expect("union", Run(user, "SELECT count(*) FROM (SELECT id FROM docs UNION ALL SELECT id FROM v)"), "2");
	Expect("correlated", Run(user, "SELECT id FROM (VALUES (1),(2)) x(id) WHERE EXISTS(SELECT 1 FROM docs d WHERE d.id=x.id)"), "1");
	Expect("hidden error", Run(user, "SELECT id FROM docs WHERE CAST(secret AS INTEGER)>0"), "1");
	Expect("query table", Run(user, "SELECT id FROM query_table('docs')"), "1");
	Expect("query literal", Run(user, "SELECT id FROM query('SELECT * FROM docs')"), "1");
	Expect("rowid survives filtering", Run(user, "SELECT rowid FROM docs"), "0");
	Expect("integer division dialect", Run(user, "SELECT id // 1 FROM docs"), "1");
	Expect("sample after row filtering", Run(user, "SELECT count(*) FROM (SELECT * FROM docs USING SAMPLE 100%)"), "1");
	Expect("pivot", Run(user, "PIVOT docs ON owner IN ('alice','bob') USING count(*) GROUP BY id"), "1");
	Refused("insert", Run(user, "INSERT INTO docs VALUES(3,'alice','33')"), "write-check");
	Refused("update", Run(user, "UPDATE docs SET owner='alice'"), "write-check");
	Refused("delete", Run(user, "DELETE FROM docs"), "write-check");
	Refused("drop", Run(user, "DROP TABLE docs"), "write-check");
	Run(setup, "CREATE SCHEMA guarded; CREATE TABLE guarded.docs AS SELECT * FROM memory.main.docs");
	handle.row_policies.push_back({"p-schema", "memory", "guarded", "docs", "owner = 'alice'"});
	++handle.policy_version;
	install();
	Refused("drop containing schema", Run(user, "DROP SCHEMA guarded CASCADE"), "write-check");
	handle.row_policies.resize(1);
	++handle.policy_version;
	install();
	Expect("prepare", Run(user, "PREPARE visible AS SELECT id FROM docs"), "");
	Expect("execute", Run(user, "EXECUTE visible"), "1");
	handle.row_policies[0].sql = "owner = 'bob'";
	++handle.policy_version;
	install();
	Expect("rebind after policy change", Run(user, "EXECUTE visible"), "2");
	handle.row_policies[0].sql = "owner = 'alice'";
	install();
	Expect("rebind after signed policy changes at same version", Run(user, "EXECUTE visible"), "1");
	handle.row_policies.push_back({"p2", "memory", "main", "docs", "id = 2"});
	++handle.policy_version;
	install();
	Expect("OR composition", Run(user, "SELECT count(*) FROM docs"), "2");
	handle.row_policies.resize(1);
	handle.row_policies[0].sql = "CAST(secret AS INTEGER)>0";
	++handle.policy_version;
	install();
	Expect("policy error does not disclose value", Run(user, "SELECT id FROM docs"), "1");
	handle.row_policies.push_back({"p2", "memory", "main", "docs", "id = 2"});
	++handle.policy_version;
	install();
	Expect("one policy error does not cancel another grant", Run(user, "SELECT count(*) FROM docs"), "2");
	handle.row_policies.resize(1);
	handle.row_policies[0].sql = "owner = 'alice') OR (true";
	++handle.policy_version;
	install();
	Refused("each policy parses independently", Run(user, "SELECT id FROM docs"), "could not be bound");
	handle.row_policies[0].sql = "docs.owner = 'alice'";
	++handle.policy_version;
	install();
	Expect("qualified predicate", Run(user, "SELECT id FROM docs"), "1");
	Expect("set caller collation", Run(user, "SET default_collation='nocase'"), "");
	Refused("caller cannot change policy collation", Run(user, "SELECT id FROM docs"), "could not be bound");
	Expect("reset caller collation", Run(user, "RESET default_collation"), "");
	Expect("read after reset", Run(user, "SELECT id FROM docs"), "1");
	handle.row_policies[0].sql = "lower(owner) = 'alice'";
	++handle.policy_version;
	install();
	Expect("built-in function in policy", Run(user, "SELECT id FROM docs"), "1");
	Run(setup, "CREATE MACRO memory.main.lower(v) AS 'alice'");
	Refused("macro cannot shadow policy function", Run(user, "SELECT id FROM docs"), "could not be bound");
	Run(setup, "DROP MACRO memory.main.lower");
	handle.row_policies[0].sql = "missing = 'alice'";
	++handle.policy_version;
	install();
	Refused("outer scope cannot supply missing column", Run(user,
	        "SELECT d.id FROM (SELECT 'alice' AS missing) caller, docs d"), "could not be bound");
	handle.row_policies[0].sql = "id IN (SELECT id FROM docs)";
	++handle.policy_version;
	install();
	Refused("subquery policy", Run(user, "SELECT id FROM docs"), "could not be bound");
	handle.row_policies[0].sql = "owner = 'alice'";
	handle.row_policies[0].table = "v";
	++handle.policy_version;
	install();
	Refused("view-scoped policy", Run(user, "SELECT id FROM v"), "scoped to a view");
	handle.row_policies[0].table = "docs";
	++handle.policy_version;
	install();
	tamper = true;
	Refused("later extension removes filter", Run(user, "SELECT id FROM docs"), "placement");
	tamper = false;
	handle.row_policies.clear();
	++handle.policy_version;
	install();
	Expect("no policy", Run(user, "SELECT count(*) FROM docs"), "2");
	Expect("internal connection bypass", Run(setup, "SELECT count(*) FROM docs"), "2");
	handle.row_policies = {{"p1", "memory", "main", "docs", "owner = 'alice'"}};
	handle.superuser = true;
	++handle.policy_version;
	Connection admin(db);
	admin.context->registered_state->Insert(qod::kQuackSessionKey, make_shared_ptr<Client>());
	Expect("superuser install", Run(admin, qod_test::Prelude(qod_test::MintWith(handle))), "true");
	Expect("superuser bypass", Run(admin, "SELECT count(*) FROM docs"), "2");
	// The extension is optional on developer machines; CI stages the pinned DuckLake build.
	auto loaded = Run(setup, "LOAD ducklake");
	if (loaded.rfind("ERROR: ", 0) != 0) {
		auto dir = std::filesystem::temp_directory_path() / ("qod-row-policy-" + std::to_string(qod::SystemNowMs()));
		std::filesystem::create_directories(dir);
		auto path = dir.generic_string();
		Expect("DuckLake attach", Run(setup, "ATTACH 'ducklake:" + path + "/catalog.duckdb' AS lake "
		       "(DATA_PATH '" + path + "/data/')"), "");
		Run(setup, "CREATE TABLE lake.main.docs AS SELECT * FROM memory.main.docs");
		Run(setup, "CREATE VIEW lake.main.v AS SELECT * FROM lake.main.docs");
		handle.superuser = false;
		handle.row_policies[0].catalog = "lake";
		++handle.policy_version;
		install();
		Expect("DuckLake base scan", Run(user, "SELECT id FROM lake.main.docs"), "1");
		Expect("DuckLake view", Run(user, "SELECT id FROM lake.main.v"), "1");
		Expect("DuckLake count", Run(user, "SELECT count(*) FROM lake.main.docs"), "1");
		Expect("DuckLake caller error", Run(user, "SELECT id FROM lake.main.docs WHERE CAST(secret AS INTEGER)>0"), "1");
		Run(setup, "DETACH lake");
		std::filesystem::remove_all(dir);
	} else {
		std::cout << "DuckLake cases skipped: pinned extension is not installed" << std::endl;
	}
	std::cout << "row_policy_engine: " << checks << " checks, " << failures << " failures" << std::endl;
	return failures ? 1 : 0;
}
