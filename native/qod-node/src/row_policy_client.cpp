#include "row_policy_client.hpp"
#include "crypto.hpp"
#include "json.hpp"
#include "json_writer.hpp"
#include <stdexcept>

namespace qod {
RowPolicyClient::RowPolicyClient(std::string node, std::string key, int64_t timeout, std::function<int64_t()> clock,
								 Transport transport)
	: node_(std::move(node)), key_(std::move(key)), timeout_(timeout), clock_(std::move(clock)),
	  transport_(std::move(transport)) {
	if (!transport_)
		transport_ = [](auto &url, auto &path, auto &headers, auto &body, auto timeout) {
			return HttpPost(url, path, headers, body, timeout);
		};
}

std::vector<RowPolicy> RowPolicyClient::Fetch(const Handle &handle, const std::string &text) {
	if (handle.row_policy_bundle.empty())
		return handle.row_policies;
	const auto cache_key = handle.replica + "|" + handle.row_policy_bundle;
	{
		std::lock_guard<std::mutex> guard(lock_);
		auto found = kept_.find(cache_key);
		if (found != kept_.end() && found->second.until > clock_())
			return found->second.rows;
	}
	std::vector<RowPolicy> rows;
	size_t bytes = 0;
	int64_t offset = 0;
	auto deadline = clock_() + timeout_;
	auto url = ParseHttpUrl(handle.replica);
	for (;;) {
		auto remaining = deadline - clock_();
		if (remaining <= 0)
			throw std::runtime_error("policy snapshot timeout");
		auto body = CanonicalJson(JsonObject({{"handle", JsonString(text)}, {"offset", JsonInt(offset)}}));
		auto reply = transport_(url, url.path + "/api/internal/authz/row-policies",
								{{"Content-Type", "application/json"},
								 {"X-QoD-Node", node_},
								 {"X-QoD-Signature", Hex(HmacSha256(key_, body))}},
								body, remaining);
		if (reply.status != 200)
			throw std::runtime_error("policy snapshot unavailable");
		auto dot = reply.body.find('.');
		if (dot == std::string::npos || reply.body.find('.', dot + 1) != std::string::npos)
			throw std::runtime_error("policy snapshot signature refused");
		auto payload = FromBase64Url(reply.body.substr(0, dot));
		auto signature = reply.body.substr(dot + 1);
		if (signature.size() != 64 || !ConstantTimeEquals(signature, Hex(HmacSha256(key_, payload))))
			throw std::runtime_error("policy snapshot signature refused");
		bytes += payload.size();
		if (bytes > kMaxRowPolicyBundleBytes)
			throw std::runtime_error("policy snapshot exceeds capacity");
		auto page = ParseJson(payload);
		if (page.Get("v", JsonValue::Kind::INTEGER).integer != 1 ||
			page.Get("bundle", JsonValue::Kind::STRING).string != handle.row_policy_bundle ||
			page.Get("offset", JsonValue::Kind::INTEGER).integer != offset)
			throw std::runtime_error("policy snapshot page refused");
		auto &entries = page.Get("rows", JsonValue::Kind::ARRAY).array;
		if (entries.empty() || entries.size() > 64)
			throw std::runtime_error("policy snapshot page refused");
		for (auto &p : entries) {
			RowPolicy row{p.Get("id", JsonValue::Kind::STRING).string, p.Get("catalog", JsonValue::Kind::STRING).string,
						  p.Get("schema", JsonValue::Kind::STRING).string,
						  p.Get("table", JsonValue::Kind::STRING).string, p.Get("sql", JsonValue::Kind::STRING).string};
			if (row.id.empty() || row.catalog.empty() || row.schema.empty() || row.table.empty() || row.sql.empty() ||
				row.sql.size() > 65536)
				throw std::runtime_error("invalid policy snapshot entry");
			rows.push_back(std::move(row));
		}
		auto next = page.Get("next", JsonValue::Kind::INTEGER).integer;
		if (next == -1)
			break;
		if (next != offset + static_cast<int64_t>(entries.size()))
			throw std::runtime_error("policy snapshot page refused");
		offset = next;
	}
	{
		std::lock_guard<std::mutex> guard(lock_);
		for (auto it = kept_.begin(); it != kept_.end();) {
			if (it->second.until <= clock_() || bytes_ + bytes > kMaxRowPolicyBundleBytes || kept_.size() >= 1024) {
				bytes_ -= it->second.bytes;
				it = kept_.erase(it);
			} else
				++it;
		}
		auto old = kept_.find(cache_key);
		if (old != kept_.end()) {
			bytes_ -= old->second.bytes;
			kept_.erase(old);
		}
		kept_[cache_key] = Kept{rows, clock_() + 5 * 60 * 1000, bytes};
		bytes_ += bytes;
	}
	return rows;
}
} // namespace qod
