#!/usr/bin/env bash
# smoke-test.sh [--manager | --queries | --all]: checks a container install (README.md). Run as root.
#
#   --manager   the manager alone: /ready, TLS on 31338 and 9494 (and 443 when something listens
#               there), the host's token, every port bound as spec section 8 says, no published
#               Docker port, and no secret where a node or the journal can see it.
#   --queries   once the workers are approved: a throwaway tenant with a one-node pool, then a
#               write and a read, a refusal (RBAC), a mask (column policy) and a time-travel read,
#               each through MCP run_sql with a tenant user's own token. Removed at the end.
# Prints one line per check, PASS or FAIL; exits 1 when any fails. Never prints a secret.
set -uo pipefail
HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"
need_root
load_host_env

FAILS=0
pass() { printf 'PASS  %s\n' "$*"; }
fail() { printf 'FAIL  %s\n' "$*"; FAILS=$((FAILS + 1)); }
check() { local what="$1"; shift; if "$@" >/dev/null 2>&1; then pass "$what"; else fail "$what"; fi; }

tls_ok() { echo | timeout 5 openssl s_client -connect "127.0.0.1:$1" ${2:+-alpn "$2"} 2>/dev/null | grep -q 'BEGIN CERTIFICATE'; }

# The local addresses a port listens on, one per line.
listen_addrs() { ss -Hltn "sport = :$1" | awk '{print $4}' | sed 's/:[0-9]*$//' | sort -u; }
loopback_only() {
  local a addrs; addrs="$(listen_addrs "$1")"
  [[ -n "$addrs" ]] || return 1
  while IFS= read -r a; do [[ "$a" == 127.0.0.1 || "$a" == "[::1]" || "$a" == "[::ffff:127.0.0.1]" ]] || return 1; done <<<"$addrs"
}

manager_checks() {
  check "manager /ready answers 200" curl -fsS -o /dev/null "$MANAGER_URL/ready"
  check "Flight SQL on :31338 speaks TLS" tls_ok 31338 h2
  check "Quack on :9494 speaks TLS" tls_ok 9494
  if [[ -n "$(listen_addrs 443)" ]]; then check "HTTPS on :443 (the reverse proxy)" tls_ok 443; fi
  check "the host's token signs in (GET /api/auth/whoami)" api GET /api/auth/whoami

  local p
  for p in 20900 20901; do check "port $p listens on loopback only" loopback_only "$p"; done
  for i in $WORKER_IDS; do
    p="$(worker_port "$i")"
    if [[ -n "$(listen_addrs "$p")" ]]; then check "node port $p listens on loopback only" loopback_only "$p"; fi
  done
  [[ "$POSTGRES" == bundled ]] && check "PostgreSQL :5432 listens on loopback only" loopback_only 5432
  local published
  published="$(docker ps --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Names}} {{.Ports}}' | awk 'NF > 1')"
  if [[ -z "$published" ]]; then pass "no QoD container publishes a Docker port"; else fail "containers publish ports: $(awk '{print $1}' <<<"$published" | xargs)"; fi

  # Secrets of qod.env and postgres.env that must appear nowhere but there and in the manager's
  # own container: compared by count, never printed.
  local sec; sec="$(mktemp)"; chmod 600 "$sec"
  grep -h -E '^(QOD_PG_PASSWORD|QOD_ADMIN_PASSWORD|QOD_S3_SECRET_ACCESS_KEY|QOD_MANAGED_STORE_SECRET_ACCESS_KEY|QOD_AZURE_CONNECTION_STRING|QOD_SESSION_JWT_SECRET|QOD_NODE_KEY_SECRET|POSTGRES_PASSWORD)=' \
    "$QOD_ENV" "$PG_ENV" 2>/dev/null | cut -d= -f2- | awk 'length($0) >= 8' > "$sec"
  if [[ -s "$sec" ]]; then
    local hits=0 c n
    for i in $WORKER_IDS; do
      c="$(worker_container "$i")"
      n="$(docker exec "$c" sh -c 'for f in /proc/[0-9]*/environ /proc/[0-9]*/cmdline; do cat "$f" 2>/dev/null; echo; done' | tr '\0' '\n' | grep -c -F -f "$sec" || true)"
      hits=$((hits + n))
      n="$(docker inspect "$c" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -c -F -f "$sec" || true)"
      hits=$((hits + n))
    done
    if (( hits == 0 )); then pass "no manager secret in any worker's processes or container settings"; else fail "$hits manager secret(s) visible in workers"; fi
    # shellcheck disable=SC2009  # every command line, not one process
    n="$(ps -eo args= | grep -c -F -f "$sec" || true)"
    if (( n == 0 )); then pass "no secret on any command line of this host"; else fail "$n command line(s) of this host hold a secret"; fi
    local tags=(CONTAINER_TAG=qod-manager CONTAINER_TAG=qod-postgres)
    for i in $WORKER_IDS; do tags+=("CONTAINER_TAG=$(worker_container "$i")"); done
    n="$(journalctl -q --no-pager -o cat "${tags[@]}" 2>/dev/null | grep -c -F -f "$sec" || true)"
    if (( n == 0 )); then pass "no secret in the containers' journal"; else fail "$n journal line(s) hold a secret"; fi
  fi
  rm -f "$sec"
}

# ---------------------------------------------------------------------------------------------
# Query checks
# ---------------------------------------------------------------------------------------------
T="" # the throwaway tenant

post() { api POST "$1"; }
# mcp TOKEN_FILE TOOL ARGS_JSON: the tool's result text on stdout; exit 1 when it answered an error.
mcp() {
  local tok="$1" tool="$2" args="$3" hdr out
  hdr="$(mktemp)"; chmod 600 "$hdr"
  printf 'Authorization: Bearer %s\n' "$(cat "$tok")" > "$hdr"
  out="$(jq -n --arg t "$tool" --argjson a "$args" \
      '{jsonrpc: "2.0", id: 1, method: "tools/call", params: {name: $t, arguments: $a}}' |
    curl -fsS -H @"$hdr" -H 'Content-Type: application/json' --data-binary @- "$MANAGER_URL/mcp")"
  rm -f "$hdr"
  jq -r '.result.content[0].text // .error.message // empty' <<<"$out"
  [[ "$(jq -r '.result.isError // false' <<<"$out")" == false && "$(jq -r 'has("error")' <<<"$out")" == false ]]
}
# run_sql TOKEN_FILE SQL, on the smoke pool.
run_sql() { mcp "$1" run_sql "$(jq -n --arg s "$2" --arg d "${T}_smoke" '{sql: $s, database: $d, pool: "p"}')"; }

# user_token NAME PASSWORD_FILE OUT_FILE: the user's own access token, from a tenant sign-in.
user_token() {
  local session hdr
  session="$(jq -Rs --arg t "$T" --arg u "$1" '{tenant: $t, username: $u, password: .}' < "$2" |
    curl -fsS -H 'Content-Type: application/json' --data-binary @- "$MANAGER_URL/api/auth/login" | jq -r .token)" || return 1
  hdr="$(mktemp)"; chmod 600 "$hdr"; printf 'X-API-Key: %s\n' "$session" > "$hdr"
  jq -n '{name: "smoke-test"}' | curl -fsS -H @"$hdr" -H 'Content-Type: application/json' --data-binary @- \
    "$MANAGER_URL/api/auth/pat/create" | jq -r .token > "$3"
  rm -f "$hdr"
  [[ -s "$3" ]]
}

cleanup_queries() {
  [[ -n "$T" ]] || return 0
  jq -n --arg t "$T" '{tenant: $t, tenantDb: ($t + "_smoke"), pool: "p", force: true}' | post /api/pool/delete >/dev/null 2>&1
  for _ in $(seq 1 30); do
    jq -n --arg t "$T" '{tenant: $t, name: ($t + "_smoke")}' | post /api/database/delete >/dev/null 2>&1 && break
    sleep 2
  done
  jq -n --arg t "$T" '{name: $t}' | post /api/tenant/delete >/dev/null 2>&1 ||
    say "could not delete the smoke tenant $T: qod tenant delete $T"
  rm -rf -- "$WORK"
}

query_checks() {
  T="smk$(openssl rand -hex 3)"
  WORK="$(mktemp -d)"; chmod 700 "$WORK"
  trap cleanup_queries EXIT
  openssl rand -hex 12 > "$WORK/alice.pw"; openssl rand -hex 12 > "$WORK/bob.pw"; openssl rand -hex 12 > "$WORK/carol.pw"

  jq -n --arg t "$T" '{id: $t, displayName: "QoD smoke test"}' | post /api/tenant/create >/dev/null ||
    { fail "create the smoke tenant"; return; }
  jq -n --arg t "$T" '{tenant: $t, name: "smoke", kind: "ducklake"}' | post /api/database/create >/dev/null ||
    { fail "create a DuckLake database"; return; }
  jq -n --arg t "$T" '{tenant: $t, tenantDb: ($t + "_smoke"), pool: "p", size: 1, roleDistribution: {writeonly: 0, readonly: 0, dual: 1}}' |
    post /api/pool/create >/dev/null || { fail "create a one-node pool"; return; }

  # alice: the default built-ins (every table). bob: no table. carol: reads, with s masked.
  local role
  role="$(jq -n --arg t "$T" '{tenant: $t, name: "masked"}' | post /api/role/create | jq -r .id)"
  jq -n --arg r "$role" '{roleId: $r, verb: "RO"}' | post /api/role/permission/grant >/dev/null
  jq -n --arg r "$role" '{roleId: $r, columnName: "s", action: "mask", transformSql: "'"'"'***'"'"'"}' |
    post /api/role/column-policy/create >/dev/null || fail "create a column policy"
  local u roles
  for u in alice bob carol; do
    case "$u" in alice) roles=null ;; bob) roles='["qod_no_tables"]' ;; carol) roles='["masked"]' ;; esac
    jq -Rs --arg t "$T" --arg u "$u" --argjson r "$roles" \
      '{tenant: $t, username: $u, password: ., kind: "user"} + (if $r == null then {} else {roles: $r} end)' < "$WORK/$u.pw" |
      post /api/user/create >/dev/null || { fail "create user $u"; return; }
    user_token "$u" "$WORK/$u.pw" "$WORK/$u.tok" || { fail "sign in as $u and create a token"; return; }
  done
  pass "a tenant, a DuckLake database, a one-node pool and three users"

  local up=0
  for _ in $(seq 1 90); do run_sql "$WORK/alice.tok" "SELECT 1" >/dev/null 2>&1 && { up=1; break; }; sleep 2; done
  (( up )) || { fail "the pool's node answers within 3 minutes"; return; }
  pass "the pool's node runs on a worker and answers"

  local out
  if run_sql "$WORK/alice.tok" "CREATE TABLE t AS SELECT range AS id, 'secret-' || range AS s FROM range(100)" >/dev/null &&
     out="$(run_sql "$WORK/alice.tok" "SELECT count(*) AS n FROM t")" && grep -q '100' <<<"$out"; then
    pass "write and read back (alice)"
  else fail "write and read back (alice)"; fi

  if out="$(run_sql "$WORK/bob.tok" "SELECT count(*) FROM t")"; then fail "a user with no table grant is refused (bob read t)"
  else pass "a user with no table grant is refused (bob)"; fi

  if out="$(run_sql "$WORK/carol.tok" "SELECT s FROM t ORDER BY id LIMIT 1")" && grep -q '\*\*\*' <<<"$out" && ! grep -q 'secret-' <<<"$out"; then
    pass "a masked column reads its mask (carol)"
  else fail "a masked column reads its mask (carol)"; fi

  local v
  # The latest snapshot, from MCP list_snapshots with the host's token: the tool is an admin's, and
  # the snapshot table functions are superuser-only. The read itself is alice's.
  v="$(mcp "$TOKEN_FILE" list_snapshots "$(jq -n --arg d "${T}_smoke" --arg t "$T" '{database: $d, tenant: $t}')" | tee "$WORK/snapshots.json" |
    jq -r '[.. | objects | (.snapshotId // .snapshot_id // empty)] | max // empty' 2>/dev/null)"
  if [[ -n "$v" ]] && run_sql "$WORK/alice.tok" "INSERT INTO t SELECT range + 100, 'x' FROM range(5)" >/dev/null &&
     out="$(run_sql "$WORK/alice.tok" "SELECT count(*) AS n FROM t AT (VERSION => $v)")" && grep -q '100' <<<"$out" &&
     out="$(run_sql "$WORK/alice.tok" "SELECT count(*) AS n FROM t")" && grep -q '105' <<<"$out"; then
    pass "time travel reads the table as of an earlier snapshot"
  else fail "time travel reads the table as of an earlier snapshot"; fi
}

case "${1:---all}" in
  --manager) manager_checks ;;
  --queries) query_checks ;;
  --all) manager_checks; query_checks ;;
  *) die "usage: smoke-test.sh [--manager | --queries | --all]" ;;
esac
(( FAILS == 0 )) || { say "$FAILS check(s) failed"; exit 1; }
