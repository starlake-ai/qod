#include "column_policies.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/planner/expression/bound_constant_expression.hpp"
#include "duckdb/planner/planner_extension.hpp"
#include "node_hooks.hpp"
#include "test_handles.hpp"

#include <iostream>
#include <filesystem>

namespace {
using namespace duckdb;
int checks = 0;
int failures = 0;
int64_t now = 1761200000000;
int tamper = 0;
struct Client : ClientContextState {};

std::string Run(Connection &con, const std::string &sql) {
	auto result = con.Query(sql);
	if (result->HasError())
		return "ERROR: " + result->GetError();
	std::string out;
	while (auto chunk = result->FetchRaw()) {
		for (idx_t row = 0; row < chunk->size(); ++row) {
			for (idx_t col = 0; col < chunk->ColumnCount(); ++col) {
				out += (out.empty() ? "" : ",") + chunk->GetValue(col, row).ToString();
			}
		}
	}
	return out;
}

void Expect(const std::string &what, const std::string &got, const std::string &expected) {
	++checks;
	if (got != expected) {
		std::cerr << "FAIL " << what << ": expected " << expected << ", got " << got << "\n";
		++failures;
	}
}

void Refused(const std::string &what, const std::string &got, const std::string &reason) {
	++checks;
	if (got.rfind("ERROR: ", 0) != 0 || got.find(reason) == std::string::npos) {
		std::cerr << "FAIL " << what << ": expected refusal " << reason << ", got " << got << "\n";
		++failures;
	}
}

void Tamper(PlannerExtensionInput &, BoundStatement &statement) {
	if (!tamper || !statement.plan)
		return;
	std::function<void(unique_ptr<LogicalOperator> &)> walk = [&](unique_ptr<LogicalOperator> &op) {
		if (dynamic_cast<qod::ColumnPolicyBoundary *>(op.get())) {
			if (tamper == 1)
				op = std::move(op->children[0]->children[0]);
			else
				op->children[0]->expressions[0] = make_uniq<BoundConstantExpression>(Value("changed"));
			return;
		}
		for (auto &child : op->children)
			walk(child);
	};
	walk(statement.plan);
}
} // namespace

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
	Expect("seed",
	       Run(setup, "CREATE TABLE docs(id INTEGER, owner VARCHAR, secret VARCHAR); "
	                  "INSERT INTO docs VALUES "
	                  "(1,'alice','raw-alice'),(2,'bob','raw-bob'); "
	                  "CREATE VIEW v AS SELECT id, secret AS renamed FROM docs; "
	                  "CREATE VIEW computed AS SELECT id, upper(secret) AS value FROM "
	                  "docs; "
	                  "CREATE SECURE VIEW sv AS SELECT id, secret FROM main.docs; "
	                  "CREATE SCHEMA other; CREATE TABLE other.docs AS SELECT * FROM docs; "
	                  "CREATE TABLE generated(id INTEGER, secret VARCHAR, derived VARCHAR "
	                  "GENERATED ALWAYS AS (upper(secret))); "
	                  "INSERT INTO generated(id,secret) VALUES(1,'raw-generated'); "
	                  "CREATE TABLE \"Clés\"(id INTEGER, secret VARCHAR); "
	                  "INSERT INTO \"Clés\" VALUES(1,'raw-unicode'); "
	                  "CREATE TABLE dotted(\"a.b\" VARCHAR, key VARCHAR, current_date VARCHAR); "
	                  "INSERT INTO dotted VALUES('raw-dot','raw-key','raw-date')"),
	       "1");
	Connection user(db);
	user.context->registered_state->Insert(qod::kQuackSessionKey, make_shared_ptr<Client>());
	qod_test::MintSpec handle;
	handle.principal_id = "alice";
	handle.exp = now + 60000;
	qod::ColumnPolicy mask {"m1", "memory", "main", "docs", "secret", "mask", "secret || '!'"};
	auto install = [&] {
		Expect("install", Run(user, qod_test::Prelude(qod_test::MintWith(handle))), "true");
	};
	handle.column_policies = {mask};
	install();
	Expect("direct", Run(user, "SELECT secret FROM docs ORDER BY id"), "raw-alice!,raw-bob!");
	Expect("renamed view", Run(user, "SELECT renamed FROM v ORDER BY id"), "raw-alice!,raw-bob!");
	Expect("computed view", Run(user, "SELECT value FROM computed ORDER BY id"), "RAW-ALICE!,RAW-BOB!");
	Expect("secure view", Run(user, "SELECT secret FROM sv ORDER BY id"), "raw-alice!,raw-bob!");
	Expect("derived exactly once", Run(user, "SELECT e FROM (SELECT secret AS e FROM docs) d ORDER BY e"),
	       "raw-alice!,raw-bob!");
	Expect("CTE exactly once",
	       Run(user, "WITH d AS MATERIALIZED (SELECT secret FROM docs) SELECT "
	                 "secret FROM d ORDER BY secret"),
	       "raw-alice!,raw-bob!");
	Expect("count", Run(user, "SELECT count(*) FROM docs"), "2");
	Expect("predicate uses mask", Run(user, "SELECT count(*) FROM docs WHERE secret='raw-alice'"), "0");
	Expect("both join sides", Run(user, "SELECT count(*) FROM docs a JOIN docs b ON a.secret=b.secret"), "2");
	Expect("NATURAL join uses masks", Run(user, "SELECT count(*) FROM docs NATURAL JOIN other.docs"), "0");
	Expect("query literal", Run(user, "SELECT renamed FROM query('SELECT * FROM v') ORDER BY id"),
	       "raw-alice!,raw-bob!");
	Expect("positional", Run(user, "SELECT #3 FROM docs ORDER BY id"), "raw-alice!,raw-bob!");
	Expect("CTE shadow", Run(user, "WITH docs AS (SELECT 'public' AS secret) SELECT secret FROM docs"), "public");
	Expect("correlated expression", Run(user, "SELECT (SELECT a.secret) FROM docs a ORDER BY id"),
	       "raw-alice!,raw-bob!");
	Expect("outer join keeps NULL extension",
	       Run(user, "SELECT d.secret FROM (VALUES(1),(3)) x(i) LEFT JOIN docs d "
	                 "ON d.id=x.i ORDER BY x.i"),
	       "raw-alice!,NULL");
	Expect("USING join sees masked values", Run(user, "SELECT count(*) FROM docs JOIN other.docs USING(secret)"), "0");
	Expect("whole-row value has no raw path",
	       Run(user, "SELECT d.secret FROM (SELECT docs AS d FROM docs) x ORDER BY d.id"), "raw-alice!,raw-bob!");
	Expect("list consumer", Run(user, "SELECT list(secret ORDER BY id) FROM docs"), "[raw-alice!, raw-bob!]");

	auto unicode = mask;
	unicode.table = "Clés";
	handle.column_policies = {unicode};
	install();
	Expect("ASCII identifier case", Run(user, "SELECT SECRET FROM \"CLéS\""), "raw-unicode!");
	Expect("non-ASCII CTE does not hide source",
	       Run(user, "WITH \"clÉs\" AS (SELECT 'cte' AS secret) SELECT secret FROM \"Clés\""), "raw-unicode!");
	Expect("ASCII-equivalent CTE shadows source",
	       Run(user, "WITH \"CLéS\" AS (SELECT 'cte' AS secret) SELECT secret FROM \"Clés\""), "cte");
	auto dotted = mask;
	dotted.table = "dotted";
	dotted.column = "a.b";
	dotted.sql = "\"a.b\" || '!'";
	handle.column_policies = {dotted};
	install();
	Expect("dotted source column", Run(user, "SELECT d.\"a.b\" FROM dotted d"), "raw-dot!");
	Expect("dotted source star", Run(user, "SELECT d.* FROM dotted d"), "raw-dot!,raw-key,raw-date");
	Expect("dotted column excluded", Run(user, "SELECT * EXCLUDE(\"a.b\") FROM dotted"), "raw-key,raw-date");
	Expect("caller star replacement", Run(user, "SELECT * REPLACE('caller' AS \"a.b\") FROM dotted"),
	       "caller,raw-key,raw-date");
	auto keyword = dotted;
	keyword.column = "key";
	keyword.sql = "\"key\" || '!'";
	handle.column_policies = {keyword};
	install();
	Expect("keyword source column", Run(user, "SELECT key FROM dotted"), "raw-key!");
	keyword.column = "current_date";
	keyword.sql = "\"current_date\" || '!'";
	handle.column_policies = {keyword};
	install();
	Expect("SQL value name binds source", Run(user, "SELECT current_date FROM dotted"), "raw-date!");
	handle.column_policies = {mask};
	install();
	auto labeled = user.Query("SELECT upper(secret) FROM docs");
	Expect("result name excludes mask",
	       labeled->HasError() ? labeled->GetError() : labeled->ColumnName(0).GetIdentifierName(),
	       setup.Query("SELECT upper(secret) FROM docs")->ColumnName(0).GetIdentifierName());

	auto explanation = Run(user, "EXPLAIN SELECT secret FROM docs");
	Expect("EXPLAIN hides masks", explanation.find("||") == std::string::npos ? "hidden" : explanation, "hidden");

	auto constant = mask;
	constant.sql = "'policy-salt-DO-NOT-EXPOSE'";
	handle.column_policies = {constant};
	install();
	explanation = Run(user, "EXPLAIN SELECT secret FROM docs");
	Expect("EXPLAIN hides constants",
	       explanation.find("policy-salt-DO-NOT-EXPOSE") == std::string::npos ? "hidden" : explanation, "hidden");
	Expect("aggregate sees mask", Run(user, "SELECT count(DISTINCT secret) FROM docs"), "1");
	Expect("outer join never masks introduced NULL",
	       Run(user, "SELECT d.secret FROM (VALUES(3)) x(i) LEFT JOIN docs d ON d.id=x.i"), "NULL");
	handle.column_policies = {mask};
	install();

	auto deny = mask;
	deny.action = "deny";
	deny.sql.clear();
	handle.column_policies = {deny};
	install();
	Refused("deny view", Run(user, "SELECT renamed FROM v"), "column is denied");
	Refused("deny predicate", Run(user, "SELECT id FROM docs WHERE secret='raw-alice'"), "column is denied");
	Expect("deny unrelated column", Run(user, "SELECT id FROM docs ORDER BY id"), "1,2");
	Expect("deny count", Run(user, "SELECT count(*) FROM docs"), "2");
	handle.row_policies = {{"r1", "memory", "main", "docs", "secret='raw-alice'"}};
	install();
	Expect("RLS may privately read denied column", Run(user, "SELECT id FROM docs"), "1");
	Expect("RLS private dependency with count", Run(user, "SELECT count(*) FROM docs"), "1");
	handle.column_policies = {mask};
	install();
	Expect("RLS sees raw before mask", Run(user, "SELECT secret FROM docs"), "raw-alice!");
	handle.row_policies.clear();

	auto wildcard = mask;
	wildcard.id = "mw";
	wildcard.table = "*";
	wildcard.sql = "'wildcard'";
	handle.column_policies = {wildcard, mask};
	install();
	Expect("named mask beats wildcard", Run(user, "SELECT secret FROM docs ORDER BY id"), "raw-alice!,raw-bob!");
	Expect("schema wildcard stays scoped",
	       Run(user, "SELECT b.secret FROM docs a JOIN other.docs b ON a.id=b.id "
	                 "ORDER BY b.id"),
	       "raw-alice,raw-bob");
	auto conflict = mask;
	conflict.id = "m2";
	conflict.sql = "'other'";
	handle.column_policies = {mask, conflict};
	install();
	Refused("conflict", Run(user, "SELECT secret FROM docs"), "conflicting column masks");
	handle.column_policies.push_back(deny);
	install();
	Refused("deny wins conflicts", Run(user, "SELECT secret FROM docs"), "column is denied");

	auto incompatible = mask;
	incompatible.sql = "hash(secret)";
	handle.column_policies = {incompatible};
	install();
	Refused("type mismatch fails closed", Run(user, "SELECT secret FROM docs"), "original column type");
	auto bad = mask;
	bad.sql = "CAST(secret AS INTEGER)::VARCHAR";
	handle.column_policies = {bad};
	install();
	Expect("runtime mask errors become NULL", Run(user, "SELECT secret FROM docs ORDER BY id"), "NULL,NULL");
	auto null_mask = mask;
	null_mask.sql = "NULL";
	handle.column_policies = {null_mask};
	install();
	Expect("typed NULL", Run(user, "SELECT secret FROM docs ORDER BY id"), "NULL,NULL");
	auto number_null = null_mask;
	number_null.column = "id";
	handle.column_policies = {number_null};
	install();
	Expect("typed numeric NULL", Run(user, "SELECT id FROM docs"), "NULL,NULL");
	// A mask over a column that is not text applies once cast back to the column's own type, and is
	// refused while its result has another one (DOUBLE here).
	auto numeric_mask = number_null;
	numeric_mask.sql = "TRY_CAST(TRY_CAST(id AS DOUBLE) * 10 AS INTEGER)";
	handle.column_policies = {numeric_mask};
	install();
	Expect("numeric mask cast back to the column type", Run(user, "SELECT id FROM docs ORDER BY id"),
	       "10,20");
	auto constant_mask = number_null;
	constant_mask.sql = "0";
	handle.column_policies = {constant_mask};
	install();
	Expect("integer constant mask on an INTEGER column", Run(user, "SELECT id FROM docs"), "0,0");
	auto double_mask = number_null;
	double_mask.sql = "TRY_CAST(id AS DOUBLE) * 10";
	handle.column_policies = {double_mask};
	install();
	Refused("numeric mask of another type", Run(user, "SELECT id FROM docs"), "original column type");
	auto missing = mask;
	missing.sql = "owner";
	handle.column_policies = {missing};
	install();
	Refused("mask cannot capture another column", Run(user, "SELECT secret FROM docs"), "original column type");
	auto view_policy = mask;
	view_policy.table = "v";
	view_policy.column = "renamed";
	view_policy.sql = "'x'";
	handle.column_policies = {view_policy};
	install();
	Refused("view-scoped policy is explicit refusal", Run(user, "SELECT renamed FROM v"),
	        "column policy scoped to a view");
	handle.column_policies = {mask};
	install();
	Refused("writes to covered table", Run(user, "INSERT INTO docs VALUES(3,'alice','new')"), "column-policy table");
	Refused("update covered table", Run(user, "UPDATE docs SET owner='alice'"), "column-policy table");
	Refused("drop covered table", Run(user, "DROP TABLE docs"), "column-policy table");

	Expect("prepare", Run(user, "PREPARE p AS SELECT secret FROM docs WHERE id=1"), "");
	Expect("prepared initial mask", Run(user, "EXECUTE p"), "raw-alice!");
	auto changed = mask;
	changed.sql = "'changed'";
	handle.column_policies = {changed};
	install();
	Expect("prepared same version new signed policies", Run(user, "EXECUTE p"), "changed");
	handle.column_policies = {deny};
	install();
	Refused("prepared mask to deny", Run(user, "EXECUTE p"), "column is denied");
	handle.column_policies = {mask};
	install();
	tamper = 1;
	Refused("removed masking boundary", Run(user, "SELECT secret FROM docs"), "placement could not be verified");
	tamper = 2;
	Refused("changed mask expression", Run(user, "SELECT secret FROM docs"), "placement could not be verified");
	tamper = 0;
	handle.column_policies = {mask};
	handle.column_policies[0].table = "generated";
	install();
	Expect("generated expression reads masked input", Run(user, "SELECT derived FROM generated"), "RAW-GENERATED!");
	auto generated_policy = mask;
	generated_policy.table = "generated";
	generated_policy.column = "derived";
	generated_policy.sql = "'masked'";
	handle.column_policies = {generated_policy};
	install();
	Refused("policy on generated output refuses", Run(user, "SELECT derived FROM generated"),
	        "policies on generated columns");
	handle.column_policies = {wildcard};
	install();
	Expect("wildcard masks base source through view aliases", Run(user, "SELECT renamed FROM v"), "wildcard,wildcard");

	// A wildcard names physical source columns; unrelated view/macro names are not policy targets.
	auto no_column = deny;
	no_column.catalog = "*";
	no_column.schema = "*";
	no_column.table = "*";
	no_column.column = "ssn";
	handle.column_policies = {no_column};
	install();
	Expect("wildcard without source column does not block a view", Run(user, "SELECT id FROM v ORDER BY id"), "1,2");
	handle.column_policies.clear();
	install();
	auto histogram = Run(user, "SELECT * FROM histogram('docs', id)");
	handle.column_policies = {no_column};
	install();
	Expect("built-in table macro is not a wildcard policy target", Run(user, "SELECT * FROM histogram('docs', id)"),
	       histogram);
	handle.column_policies = {deny};
	install();
	auto before = authz->Stats().local_refusals.load();
	Refused("built-in macro retains body checks", Run(user, "SELECT * FROM histogram('docs', secret)"),
	        "query_table()");
	Expect("deny increments local refusal once", std::to_string(authz->Stats().local_refusals.load() - before), "1");
	before = authz->Stats().local_refusals.load();
	Refused("counted column deny", Run(user, "SELECT secret FROM docs"), "column is denied");
	Expect("column deny increments once", std::to_string(authz->Stats().local_refusals.load() - before), "1");
	handle.column_policies = {mask, conflict};
	install();
	before = authz->Stats().local_refusals.load();
	Refused("counted conflict", Run(user, "SELECT secret FROM docs"), "conflicting column masks");
	Expect("conflict increments local refusal once", std::to_string(authz->Stats().local_refusals.load() - before),
	       "1");
	handle.column_policies = {incompatible};
	install();
	before = authz->Stats().local_refusals.load();
	Refused("counted binding refusal", Run(user, "SELECT secret FROM docs"), "original column type");
	Expect("binding refusal increments once", std::to_string(authz->Stats().local_refusals.load() - before), "1");
	handle.column_policies = {mask};
	install();
	tamper = 1;
	before = authz->Stats().local_refusals.load();
	Refused("counted boundary tampering", Run(user, "SELECT secret FROM docs"), "placement could not be verified");
	tamper = 0;
	Expect("verification refusal increments once", std::to_string(authz->Stats().local_refusals.load() - before), "1");
	handle.column_policies.clear();
	for (int i = 0; i < 300; ++i) {
		auto unrelated = deny;
		unrelated.catalog = "db_" + std::to_string(i);
		unrelated.id = "policy_" + std::to_string(i);
		handle.column_policies.push_back(unrelated);
	}
	install();
	Expect("more than 256 unrelated policies permit SELECT 1", Run(user, "SELECT 1"), "1");
	Expect("unrelated catalogs do not mask this pool", Run(user, "SELECT secret FROM docs WHERE id=1"), "raw-alice");
	handle.column_policies.push_back(deny);
	install();
	Refused("large set retains applicable deny", Run(user, "SELECT secret FROM docs WHERE id=1"), "column is denied");
	auto loaded = Run(setup, "LOAD ducklake");
	if (loaded.rfind("ERROR: ", 0) != 0) {
		auto dir = std::filesystem::temp_directory_path() / ("qod-column-policy-" + std::to_string(qod::SystemNowMs()));
		std::filesystem::create_directories(dir);
		auto path = dir.generic_string();
		Expect("DuckLake attach",
		       Run(setup, "ATTACH 'ducklake:" + path + "/catalog.duckdb' AS lake (DATA_PATH '" + path + "/data/')"),
		       "");
		Run(setup, "CREATE TABLE lake.main.docs AS SELECT * FROM memory.main.docs");
		Run(setup, "CREATE VIEW lake.main.v AS SELECT id, upper(secret) AS value FROM lake.main.docs");
		auto lake_mask = mask;
		lake_mask.catalog = "lake";
		handle.column_policies = {lake_mask};
		install();
		Expect("DuckLake mask", Run(user, "SELECT secret FROM lake.main.docs ORDER BY id"), "raw-alice!,raw-bob!");
		Expect("DuckLake computed view", Run(user, "SELECT value FROM lake.main.v ORDER BY id"), "RAW-ALICE!,RAW-BOB!");
		Expect("DuckLake historical read", Run(user, "SELECT secret FROM lake.main.docs AT (VERSION => 1) ORDER BY id"),
		       "raw-alice!,raw-bob!");
		handle.row_policies = {{"rl", "lake", "main", "docs", "secret='raw-alice'"}};
		install();
		Expect("DuckLake raw RLS before mask", Run(user, "SELECT secret FROM lake.main.docs"), "raw-alice!");
		Refused("DuckLake change feed refuses",
		        Run(user, "SELECT * FROM ducklake_table_insertions('lake','main','docs',0,1)"),
		        "change feed with column policies");
		handle.row_policies.clear();
		Run(setup, "DETACH lake");
		std::filesystem::remove_all(dir);
	} else {
		std::cout << "DuckLake cases skipped: pinned extension is not installed\n";
	}
	std::cout << "column policy engine: " << checks << " checks, " << failures << " failures\n";
	return failures ? 1 : 0;
}
