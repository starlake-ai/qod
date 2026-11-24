#pragma once
#include "handle.hpp"
#include "http_post.hpp"
#include <functional>
#include <mutex>
#include <unordered_map>

namespace qod {
struct PolicySnapshot {
	std::vector<RowPolicy> rows;
	std::vector<ColumnPolicy> columns;
};
//! Signed, paged policy snapshots, shared by digest across connections. No policy is omitted.
class PolicyClient {
public:
	using Transport = std::function<HttpReply(const HttpUrl &, const std::string &,
	                                          const std::vector<std::pair<std::string, std::string>> &,
	                                          const std::string &, int64_t)>;
	PolicyClient(std::string node, std::string key, int64_t timeout, std::function<int64_t()> clock,
	             Transport transport = nullptr);
	PolicySnapshot Fetch(const Handle &handle, const std::string &text);

private:
	struct Kept {
		PolicySnapshot snapshot;
		int64_t until;
		size_t bytes;
	};
	std::string node_, key_;
	int64_t timeout_;
	std::function<int64_t()> clock_;
	Transport transport_;
	std::mutex lock_;
	std::unordered_map<std::string, Kept> kept_;
	size_t bytes_ = 0;
};
constexpr size_t kMaxPolicyBundleBytes = 32 * 1024 * 1024;
} // namespace qod
