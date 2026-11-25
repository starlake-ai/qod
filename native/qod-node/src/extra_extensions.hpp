#pragma once

#include "duckdb.hpp"

namespace qod {

//! Loads every extension linked through QOD_EXTRA_EXTENSIONS into `db`, before any SQL runs on it.
void LoadExtraExtensions(duckdb::DuckDB &db);

//! The linked extra extensions' names, comma-separated; "" when none (the open build).
const char *ExtraExtensionNames();

} // namespace qod
