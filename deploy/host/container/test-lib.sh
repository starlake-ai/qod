#!/usr/bin/env bash
# test-lib.sh: checks lib.sh's sizing and the compose file it renders, with no Docker and no root.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
QOD_HOST_ROOT="$(mktemp -d)"; export QOD_HOST_ROOT
trap 'rm -rf "$QOD_HOST_ROOT"' EXIT
# shellcheck source=lib.sh
source "$HERE/lib.sh"
fails=0
eq() { if [[ "$2" == "$3" ]]; then echo "ok    $1"; else echo "FAIL  $1: got '$2', want '$3'"; fails=$((fails + 1)); fi; }

# The starting points of spec section 6.2.
for row in "8 32 2 4 12" "16 64 3 6 17" "32 128 5 7 22"; do
  read -r c r n wc wm <<<"$row"
  eq "default workers for $c vCPU / $r GiB" "$(default_workers "$r")" "$n"
  eq "default worker CPUs for $c vCPU / $r GiB" "$(default_worker_cpus "$c" "$n")" "$wc"
  eq "default worker GiB for $c vCPU / $r GiB" "$(default_worker_mem "$r" "$n")" "$wm"
  eq "the defaults pass check_sizing on $c vCPU / $r GiB" "$(check_sizing "$c" "$r" "$n" "$wc" "$wm")" ""
done
eq "reserve of 32 GiB" "$(reserve_gib 32)" 4
eq "reserve of 64 GiB" "$(reserve_gib 64)" 7
eq "no spare refused" "$(check_sizing 16 64 1 4 16 | grep -c 'at least 2 workers')" 1
eq "too many CPUs refused" "$(check_sizing 16 64 3 8 16 | grep -c 'CPUs plus')" 1
eq "too much memory refused" "$(check_sizing 16 64 3 6 20 | grep -c 'GiB plus')" 1
eq "1 vCPU per 4 GiB" "$(check_sizing 32 128 3 2 12 | grep -c '1 vCPU per 4 GiB')" 1
eq "worker size parsed" "$(parse_worker_size 4cpu,16g)" "4 16"

mkdir -p "$ETC"
QOD_IMAGE=starlakeai/quack-on-demand HOST_NAME=h1 WORKER_IDS="1 3" WORKER_CPUS=5 WORKER_MEM_GIB=16 \
  SPILL_ROOT=/fastdisk/qod-spill DATA_DIR=/var/lib/qod/ducklake POSTGRES=bundled
write_host_env; load_host_env; render_compose
y="$(cat "$COMPOSE_FILE")"
eq "two workers rendered" "$(grep -c 'command: \[worker\]' <<<"$y")" 2
eq "worker 3 has its own name and port" "$(grep -c 'QOD_FLEET_NAME: h1-w3, QOD_FLEET_NODE_PORT: "21903"' <<<"$y")" 1
eq "worker 3 spills to its own folder" "$(grep -c -- '- /fastdisk/qod-spill/w3:/duckdb-tmp' <<<"$y")" 1
eq "the manager's /tmp is exec" "$(grep -c 'tmpfs: \["/tmp:exec"\]' <<<"$y")" 1
eq "every healthcheck has a timeout" "$(grep -c 'healthcheck:' <<<"$y")" "$(grep -c 'timeout:' <<<"$y")"
eq "the data dir is mounted at the same path in all three" "$(grep -c -- '- /var/lib/qod/ducklake:/var/lib/qod/ducklake' <<<"$y")" 3
eq "the bundled postgres listens on loopback" "$(grep -c 'listen_addresses=127.0.0.1' <<<"$y")" 1
eq "the manager waits for postgres" "$(grep -c 'postgres: { condition: service_healthy }' <<<"$y")" 1
eq "no published port" "$(grep -c -E '^\s+ports:' <<<"$y" || true)" 0
if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then
  printf 'QOD_VERSION=test\n' > "$VERSION_ENV"; : > "$QOD_ENV"; : > "$WORKER_ENV"; : > "$PG_ENV"
  if docker compose -p qod -f "$COMPOSE_FILE" --env-file "$VERSION_ENV" config -q; then echo "ok    docker compose config accepts the file"
  else echo "FAIL  docker compose config"; fails=$((fails + 1)); fi
fi
if (( fails == 0 )); then echo "all passed"; else echo "$fails failed"; exit 1; fi
