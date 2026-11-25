#include "build_info.hpp"

#include "duckdb/main/database.hpp"

#include <algorithm>
#include <stdexcept>
#include <string>

#define QOD_NODE_STRINGIFY_(x) #x
#define QOD_NODE_STRINGIFY(x) QOD_NODE_STRINGIFY_(x)

namespace qod {
namespace {

// One literal, assembled by the preprocessor, so the marker sits whole in the binary's read-only data:
// scripts/release/qod-node-build-info.sh finds it by scanning the file, and a string assembled at run
// time is not there to find. Only a build linking extra extensions defines QOD_NODE_EXTRA_EXTENSIONS,
// so the open build's marker is exactly the one before them.
#ifdef QOD_NODE_EXTRA_EXTENSIONS
#define QOD_NODE_EXTRA_SUFFIX ";extra=" QOD_NODE_EXTRA_EXTENSIONS
#else
#define QOD_NODE_EXTRA_SUFFIX ""
#endif

const char kBuildInfo[] = "qod-node-build-info:protocol=" QOD_NODE_STRINGIFY(QOD_NODE_PROTOCOL) ";duckdb=" QOD_NODE_DUCKDB_VERSION
                          ";duckdb_source=" QOD_NODE_DUCKDB_SOURCE_ID QOD_NODE_EXTRA_SUFFIX;

} // namespace

const char *BuildInfo() {
	return kBuildInfo;
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
