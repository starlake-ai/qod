#include "build_info.hpp"

#include "duckdb/main/database.hpp"

#include <algorithm>
#include <stdexcept>
#include <string>

#define QOD_NODE_STRINGIFY_(x) #x
#define QOD_NODE_STRINGIFY(x) QOD_NODE_STRINGIFY_(x)

namespace qod {
namespace {

#ifndef QOD_NODE_EXTRA_EXTENSIONS
#define QOD_NODE_EXTRA_EXTENSIONS ""
#endif

const char kBaseInfo[] = "qod-node-build-info:protocol=" QOD_NODE_STRINGIFY(QOD_NODE_PROTOCOL) ";duckdb=" QOD_NODE_DUCKDB_VERSION
                         ";duckdb_source=" QOD_NODE_DUCKDB_SOURCE_ID;

// The extra extensions linked through QOD_EXTRA_EXTENSIONS, only when there are any.
std::string MakeBuildInfo() {
	std::string extra = QOD_NODE_EXTRA_EXTENSIONS;
	return extra.empty() ? std::string(kBaseInfo) : std::string(kBaseInfo) + ";extra=" + extra;
}

} // namespace

const char *BuildInfo() {
	static const std::string info = MakeBuildInfo();
	return info.c_str();
}

void CheckLinkedDuckDB() {
	std::string loaded = duckdb::DuckDB::SourceID();
	std::string built = QOD_NODE_DUCKDB_SOURCE_ID;
	// A release source id can be reported abbreviated: compare on the shorter one.
	auto length = std::min(loaded.size(), built.size());
	if (length == 0 || loaded.compare(0, length, built, 0, length) != 0) {
		throw std::runtime_error(std::string("qod-node was built against DuckDB ") + QOD_NODE_DUCKDB_VERSION + " (" +
		                         built + ") but loaded " + duckdb::DuckDB::LibraryVersion() + " (" + loaded +
		                         "); install the matching libduckdb");
	}
}

} // namespace qod
