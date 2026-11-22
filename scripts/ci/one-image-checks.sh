#!/usr/bin/env bash
# one-image-checks.sh: the checks the one image owes the developer stack, run by
# .github/workflows/one-image.yml and runnable on a laptop from the repository root (Docker, compose v2,
# and a Python with adbc-driver-flightsql and pyarrow as $PYTHON).
#
#   dev-stack BUILD|<tag>   run-docker-compose.sh, unchanged, with that QOD_VERSION, DEMO=minimal and
#                           TPC-H SF1: the manager on the local runtime from the one image spawns its
#                           node inside its own container, and a tenant user's Flight SQL query answers
#   size IMAGE              the image's compressed size (docker save | gzip -6) against
#                           deploy/docker/image-size.json: fails above 110% of the baseline
#   heap-oom set|unset      a manager of the image with JAVA_OPTS=-Xmx384m (set) or with JAVA_OPTS unset
#                           in a 1536 MiB container (unset): a flood of signed-in 15 MiB bodies runs it
#                           out of heap, and it must exit and be restarted (-XX:+ExitOnOutOfMemoryError)
#   fleet                   deploy/docker/docker-compose.fleet.yml on top: its workers run the manager's
#                           image, in its worker role, and are approved and healthy
#   down                    stop-docker-compose.sh, and every container these checks started
#
# The dev stack's credentials are its .env defaults (admin / ADMIN_PASSWORD, the demo's alice): never
# use this script against a stack holding real data.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"
PYTHON="${PYTHON:-python3}"
COMPOSE=(docker compose --project-directory . -f deploy/docker/docker-compose.yml)
say() { printf 'one-image-checks: %s\n' "$*" >&2; }
die() { say "FAIL: $*"; exit 1; }
summary() { [[ -n "${GITHUB_STEP_SUMMARY:-}" ]] && printf '%s\n' "$*" >> "$GITHUB_STEP_SUMMARY"; return 0; }
env_value() { grep -E "^$1=" .env 2>/dev/null | tail -1 | cut -d= -f2- | sed 's/[[:space:]]*#.*$//'; }

wait_ready() {
  local url="$1"
  for _ in $(seq 1 "${2:-240}"); do
    curl -fsS -o /dev/null "$url" 2>/dev/null && return 0
    sleep 1
  done
  return 1
}

# ---------------------------------------------------------------------------------------------
dev_stack() {
  local mode="$1"
  say "run-docker-compose.sh with QOD_VERSION=$mode, DEMO=minimal, TPC-H SF1"
  NUKE=1 DEMO=minimal LOAD_TPCH=1 LOAD_TPCDS=0 LOAD_SSB=0 QOD_VERSION="$mode" \
    ./scripts/docker/run-docker-compose.sh < /dev/null
  local image; image="$(docker inspect quack-on-demand --format '{{.Config.Image}}')"
  say "the manager runs $image"
  [[ "$(docker inspect quack-on-demand --format '{{.Config.Entrypoint}}')" == *qod-entrypoint* ]] ||
    die "the manager's image has no qod-entrypoint: not the one image"
  "${COMPOSE[@]}" exec -T quack sh -c 'pgrep -f "qod-node --port" >/dev/null' ||
    die "no qod-node runs inside the manager's container"
  say "a node runs inside the manager's container"
  ALICE_PASSWORD=demo-alice "$PYTHON" - <<'PY' || die "the Flight SQL query did not answer"
import os, sys, time
import adbc_driver_flightsql.dbapi as fs
from adbc_driver_flightsql import DatabaseOptions as O
h = O.RPC_CALL_HEADER_PREFIX.value
kw = {"username": "alice", "password": os.environ["ALICE_PASSWORD"], O.TLS_SKIP_VERIFY.value: "true",
      h + "tenant": "acme", h + "pool": "bi"}
last = None
for _ in range(60):
    try:
        with fs.connect(uri="grpc+tls://127.0.0.1:31338", db_kwargs=kw) as c, c.cursor() as cur:
            cur.execute("SELECT count(*) FROM tpch1.region")
            n = cur.fetchone()[0]
        print(f"one-image-checks: Flight SQL as alice: tpch1.region has {n} rows", file=sys.stderr)
        sys.exit(0 if n == 5 else 1)
    except Exception as e:
        last = e
        time.sleep(5)
print(f"one-image-checks: last error: {last}", file=sys.stderr)
sys.exit(1)
PY
  summary "- dev stack with \`QOD_VERSION=$mode\`: manager \`$image\`, node in its container, Flight SQL query answered"
}

# ---------------------------------------------------------------------------------------------
size_gate() {
  local image="$1" arch bytes base
  arch="linux/$(docker image inspect "$image" --format '{{.Architecture}}')"
  say "measuring $image ($arch): docker save | gzip -6"
  bytes="$(docker save "$image" | gzip -6 | wc -c | tr -d ' ')"
  base="$(jq -r --arg a "$arch" '.compressedBytes[$a] // empty' deploy/docker/image-size.json)"
  local mb=$(( bytes / 1000000 ))
  if [[ -z "$base" ]]; then
    say "no baseline for $arch in deploy/docker/image-size.json: $bytes bytes ($mb MB); add it there"
    summary "- image size ($arch): $mb MB compressed, no baseline yet: add \`\"$arch\": $bytes\` to deploy/docker/image-size.json"
    return 0
  fi
  local limit=$(( base * 110 / 100 ))
  summary "- image size ($arch): $mb MB compressed, baseline $(( base / 1000000 )) MB, limit $(( limit / 1000000 )) MB"
  (( bytes <= limit )) || die "the image is $mb MB compressed, more than 110% of the $(( base / 1000000 )) MB baseline (deploy/docker/image-size.json): shrink it, or raise the baseline in the same pull request, saying why"
  say "size $mb MB within 110% of the $(( base / 1000000 )) MB baseline"
}

# ---------------------------------------------------------------------------------------------
heap_oom() {
  local case="$1" name="qod-oom-$1" image net admin_pw pg_pw pg_user opts=()
  image="$(docker inspect quack-on-demand --format '{{.Config.Image}}')" || die "start the dev stack first (dev-stack)"
  net="$(docker inspect quack-on-demand --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}')"
  pg_pw="$(env_value PG_PASSWORD)"; pg_pw="${pg_pw:-azizam}"
  pg_user="$(env_value PG_USER)"; pg_user="${pg_user:-postgres}"
  admin_pw="oom-$(openssl rand -hex 8)"
  docker exec quack-on-demand-postgres psql -q -U "$pg_user" -d postgres -c "DROP DATABASE IF EXISTS qodoom_$case WITH (FORCE)" -c "CREATE DATABASE qodoom_$case" >/dev/null
  if [[ "$case" == set ]]; then opts=(-e JAVA_OPTS=-Xmx384m --memory 4g)
  else opts=(--memory 1536m); fi
  docker rm -f "$name" >/dev/null 2>&1 || true
  docker run -d --name "$name" --network "$net" --restart unless-stopped -p 127.0.0.1:20990:20900 \
    -e QOD_PG_HOST=postgres -e QOD_PG_USER="$pg_user" -e QOD_PG_PASSWORD="$pg_pw" \
    -e QOD_PG_DBNAME="qodoom_$case" -e QOD_ADMIN_PASSWORD="$admin_pw" -e QOD_MIN_PORT=23100 -e QOD_MAX_PORT=23199 \
    -e QOD_QUACK_ENABLED=false -e PROXY_PORT=31990 \
    "${opts[@]}" "$image" >/dev/null
  wait_ready http://127.0.0.1:20990/ready 180 || { docker logs --tail 30 "$name"; die "the $case manager never became ready"; }
  local heap; heap="$(docker exec "$name" sh -c 'tr "\0" " " < /proc/$(pgrep -f quack-on-demand.jar | head -1)/cmdline' | grep -o -E -- '-Xmx[^ ]+|-XX:\+ExitOnOutOfMemoryError' | xargs)"
  say "heap-oom $case: manager up with ${heap:-no flags}; flooding it"
  ADMIN_PASSWORD="$admin_pw" "$PYTHON" - <<'PY' || true
import json, os, threading, urllib.request
base = "http://127.0.0.1:20990"
req = urllib.request.Request(base + "/api/auth/login", data=json.dumps({"username": "admin", "password": os.environ["ADMIN_PASSWORD"]}).encode(),
                             headers={"Content-Type": "application/json"})
token = json.load(urllib.request.urlopen(req, timeout=30))["token"]
body = json.dumps({"id": "oomtest", "displayName": "x" * (15 * 1024 * 1024)}).encode()
def one():
    r = urllib.request.Request(base + "/api/tenant/create", data=body, headers={"Content-Type": "application/json", "X-API-Key": token})
    try: urllib.request.urlopen(r, timeout=120).read()
    except Exception: pass
ts = [threading.Thread(target=one) for _ in range(60)]
[t.start() for t in ts]; [t.join() for t in ts]
PY
  local restarts=0
  for _ in $(seq 1 60); do
    restarts="$(docker inspect "$name" --format '{{.RestartCount}}')"
    (( restarts >= 1 )) && break
    sleep 2
  done
  docker logs "$name" 2>&1 | grep -q 'Terminating due to java.lang.OutOfMemoryError' ||
    { docker rm -f "$name" >/dev/null; die "heap-oom $case: no 'Terminating due to java.lang.OutOfMemoryError' in the log"; }
  (( restarts >= 1 )) || { docker rm -f "$name" >/dev/null; die "heap-oom $case: the container was not restarted"; }
  wait_ready http://127.0.0.1:20990/ready 180 || { docker rm -f "$name" >/dev/null; die "heap-oom $case: not ready again after its restart"; }
  docker rm -f "$name" >/dev/null
  docker exec quack-on-demand-postgres psql -q -U "$pg_user" -d postgres -c "DROP DATABASE IF EXISTS qodoom_$case WITH (FORCE)" >/dev/null || true
  say "heap-oom $case: exited on OutOfMemoryError, restarted ($restarts), ready again"
  summary "- heap OOM with JAVA_OPTS $case: the JVM exited on OutOfMemoryError and the restart policy brought it back"
}

# ---------------------------------------------------------------------------------------------
fleet() {
  local tag; tag="$(docker inspect quack-on-demand --format '{{.Config.Image}}')"; tag="${tag##*:}"
  grep -q '^FLEET_JOIN_TOKEN=' .env || echo "FLEET_JOIN_TOKEN=$(openssl rand -hex 24)" >> .env
  say "fleet overlay on QOD_VERSION=$tag"
  # Waits on these three by name: `--wait` on everything fails once seaweedfs-init, a one-shot, has exited.
  QOD_VERSION="$tag" "${COMPOSE[@]}" -f deploy/docker/docker-compose.fleet.yml --profile seaweedfs \
    up -d --wait --no-build quack fleet-worker-1 fleet-worker-2
  local mgr_img w img
  mgr_img="$(docker inspect quack-on-demand --format '{{.Image}}')"
  for w in quack-on-demand-fleet-worker-1 quack-on-demand-fleet-worker-2; do
    img="$(docker inspect "$w" --format '{{.Image}}')"
    [[ "$img" == "$mgr_img" ]] || die "$w runs image $img, not the manager's $mgr_img"
    [[ "$(docker inspect "$w" --format '{{json .Config.Cmd}}')" == '["worker"]' ]] || die "$w does not run the worker role"
  done
  local pw token=""; pw="$(env_value ADMIN_PASSWORD)"; pw="${pw:-admin}"
  # The overlay recreates the manager: its published port can reset a connection for a moment after
  # `up --wait` returns, so wait for /ready and retry the sign-in.
  wait_ready http://127.0.0.1:20900/ready 180 || die "the fleet-mode manager is not ready"
  for _ in $(seq 1 30); do
    token="$(jq -n --arg p "$pw" '{username: "admin", password: $p}' |
      curl -fsS -H 'Content-Type: application/json' --data-binary @- http://127.0.0.1:20900/api/auth/login 2>/dev/null | jq -r .token)" &&
      [[ -n "$token" && "$token" != null ]] && break
    sleep 2
  done
  [[ -n "$token" && "$token" != null ]] || die "cannot sign in to the fleet-mode manager"
  local n=0
  for _ in $(seq 1 60); do
    n="$(curl -fsS -H "X-API-Key: $token" http://127.0.0.1:20900/api/fleet/servers |
      jq '[.servers[] | select(.approval == "approved" and .liveness == "reachable")] | length')"
    (( n >= 2 )) && break
    sleep 2
  done
  (( n >= 2 )) || die "only $n of 2 fleet workers are approved and reachable"
  say "fleet: 2 workers on the manager's image, worker role, approved and reachable"
  summary "- fleet overlay: both workers run the manager's image in its worker role, approved and reachable"
}

down() {
  docker rm -f qod-oom-set qod-oom-unset >/dev/null 2>&1 || true
  "${COMPOSE[@]}" -f deploy/docker/docker-compose.fleet.yml --profile seaweedfs down --remove-orphans >/dev/null 2>&1 || true
  ./scripts/docker/stop-docker-compose.sh >/dev/null 2>&1 || true
}

case "${1:-}" in
  dev-stack) dev_stack "${2:?BUILD or a published tag}" ;;
  size) size_gate "${2:?image}" ;;
  heap-oom) heap_oom "${2:?set or unset}" ;;
  fleet) fleet ;;
  down) down ;;
  *) sed -n '2,20p' "$0"; exit 2 ;;
esac
