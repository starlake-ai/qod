#!/usr/bin/env bash
# Configures a second qod-node build linking the fixture extension, then checks its build marker, that
# the fixture's function is loaded, and that the open build's marker is unchanged.
# Usage: extra_extension_test.sh <source-dir> <work-dir> <open-qod-node> <duckdb-home>
set -euo pipefail
src="$1"; work="$2"; open_bin="$3"; duckdb_home="$4"
rm -rf "$work"; mkdir -p "$work/lib"
cmake -S "$src" -B "$work/fixture" -DCMAKE_BUILD_TYPE=Release -DQOD_NODE_TESTS=OFF -DDUCKDB_HOME="$duckdb_home" >/dev/null
include_dir=$(cmake -LA -N "$work/fixture" | sed -n 's/^DUCKDB_INCLUDE_DIR:PATH=//p')
c++ -std=c++17 -c "$src/tests/extra_extension_fixture.cpp" -I "$src/src" -I "$include_dir" -o "$work/lib/fixture.o"
ar rcs "$work/lib/libqod_extra_fixture.a" "$work/lib/fixture.o"
cmake -S "$src" -B "$work/linked" -DCMAKE_BUILD_TYPE=Release -DQOD_NODE_TESTS=OFF -DDUCKDB_HOME="$duckdb_home" \
  -DQOD_EXTRA_EXTENSIONS="qod_extra_fixture=$work/lib/libqod_extra_fixture.a" >/dev/null
cmake --build "$work/linked" --target qod-node >/dev/null
marker=$("$work/linked/qod-node" --build-info)
case "$marker" in *";extra=qod_extra_fixture"*) ;; *) echo "FAIL linked marker: $marker"; exit 1;; esac
functions=$("$work/linked/qod-node" --list-functions)
case "$functions" in *'"name":"qod_extra_fixture_answer"'*) ;; *) echo "FAIL fixture function not listed"; exit 1;; esac
open_marker=$("$open_bin" --build-info)
case "$open_marker" in *";extra="*) echo "FAIL open marker changed: $open_marker"; exit 1;; esac
open_functions=$("$open_bin" --list-functions)
case "$open_functions" in *qod_extra_fixture_answer*) echo "FAIL open build lists the fixture function"; exit 1;; esac
echo ok
