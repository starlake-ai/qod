#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "node_hooks.hpp"
#include "row_policy_client.hpp"
#include "test_handles.hpp"
#include "test_http_server.hpp"
#include <iostream>
#include <memory>

using namespace qod;
int failures = 0;
void Check(bool value, const char *label) {
	if (!value) {
		++failures;
		std::cerr << "FAIL " << label << '\n';
	}
}
struct Client : duckdb::ClientContextState {};
int main(int argc, char **) {
	int calls = 0;
	bool corrupt = false;
	auto answer = [&](const std::string &body) {
		++calls;
		auto request = ParseJson(body);
		auto offset = request.Get("offset", JsonValue::Kind::INTEGER).integer;
		std::vector<JsonValue> rows;
		auto end = std::min<int64_t>(300, offset + 64);
		for (int64_t i = offset; i < end; ++i)
			rows.push_back(JsonObject({{"id", JsonString("p-" + std::to_string(i))},
									   {"catalog", JsonString("memory")},
									   {"schema", JsonString("main")},
									   {"table", JsonString(i == 0 ? "docs" : "unrelated")},
									   {"sql", JsonString("owner = 'alice'")}}));
		auto payload = CanonicalJson(JsonObject({{"v", JsonInt(1)},
												 {"bundle", JsonString(std::string(64, 'a'))},
												 {"offset", JsonInt(offset)},
												 {"next", JsonInt(end == 300 ? -1 : end)},
												 {"rows", JsonArray(rows)}}));
		return ToBase64Url(payload) + "." + (corrupt ? std::string(64, '0') : Hex(HmacSha256(qod_test::kKey, payload)));
	};
	Handle handle;
	handle.row_policy_bundle = std::string(64, 'a');
	handle.replica = "http://127.0.0.1:1";
	int64_t now = 1000;
	RowPolicyClient client(
		qod_test::kNode, qod_test::kKey, 2000, [&] { return now; },
		[&](auto &, auto &, auto &headers, const std::string &body, auto) {
			Check(headers.back().second == Hex(HmacSha256(qod_test::kKey, body)), "signed request");
			return HttpReply{200, answer(body)};
		});
	Check(client.Fetch(handle, "handle").size() == 300, "all pages, more than 256 policies");
	Check(calls == 5, "bounded pages");
	Check(client.Fetch(handle, "new handle").size() == 300 && calls == 5, "snapshot cache");
	now += 6 * 60 * 1000;
	corrupt = true;
	bool refused = false;
	try {
		client.Fetch(handle, "handle");
	} catch (std::exception &) {
		refused = true;
	}
	Check(refused, "bad signature never installs policies");
	corrupt = false;
	handle.row_policy_bundle = std::string(64, 'b');
	refused = false;
	try {
		client.Fetch(handle, "handle");
	} catch (std::exception &) {
		refused = true;
	}
	Check(refused, "different snapshot never reuses a cached page");
	if (argc > 1) {
		qod_test::TestHttpServer server([&](const qod_test::HttpRequest &request) {
			Check(request.path == "/api/internal/authz/row-policies", "wire endpoint");
			return qod_test::HttpAnswer{200, answer(request.body)};
		});
		duckdb::DuckDB db(nullptr);
		auto authz = std::make_shared<NodeAuthz>(qod_test::kNode, qod_test::kKey, [&] { return now; });
		RegisterNodeAuthz(*db.instance, authz, [](const DecideCall &) {
			DecideResult r;
			r.status = DecideResult::Status::Allow;
			r.has_verdict = true;
			r.verdict.expires_at = 999999999;
			return r;
		});
		duckdb::Connection setup(db), user(db);
		setup.Query("CREATE TABLE docs(id INTEGER, owner VARCHAR); INSERT INTO docs VALUES(1,'alice'),(2,'bob')");
		user.context->registered_state->Insert(kQuackSessionKey, duckdb::make_shared_ptr<Client>());
		qod_test::MintSpec spec;
		spec.principal_id = "alice";
		spec.exp = now + 60000;
		spec.replica = server.Url();
		spec.row_policy_bundle = std::string(64, 'a');
		Check(!user.Query(qod_test::Prelude(qod_test::MintWith(spec)))->HasError(), "wire snapshot installed");
		auto result = user.Query("SELECT id FROM docs");
		auto chunk = result->HasError() ? nullptr : result->FetchRaw();
		Check(chunk && chunk->size() == 1 && chunk->GetValue(0, 0).GetValue<int32_t>() == 1,
			  "wire snapshot filters actual DuckDB scan");
	}
	std::cout << "row_policy_client: " << failures << " failures\n";
	return failures ? 1 : 0;
}
