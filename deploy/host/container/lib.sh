# shellcheck shell=bash
# shellcheck disable=SC2034  # the paths and sizes below are read by the scripts that source this
# Shared by install.sh, switch.sh, qod-host and smoke-test.sh: paths, sizing, the compose file, the
# manager's REST API. Sourced, never run. Every path hangs off QOD_HOST_ROOT (empty in production)
# so test-lib.sh can render into a scratch folder.
#
# Secrets never reach a command line or the terminal: they are generated into files (root, 600),
# handed to curl and docker through stdin or --env-file, and only their file names are printed.

ROOT="${QOD_HOST_ROOT:-}"
ETC="$ROOT/etc/qod"
VAR="$ROOT/var/lib/qod"
HOST_DIR="$ROOT/opt/qod/host"
UNIT_DIR="$ROOT/etc/systemd/system"
HOST_ENV="$ETC/host.env"
VERSION_ENV="$ETC/version.env"
QOD_ENV="$ETC/qod.env"
WORKER_ENV="$ETC/worker.env"
PG_ENV="$ETC/postgres.env"
COMPOSE_FILE="$ETC/compose.yaml"
TOKEN_FILE="$ETC/host.token"
TLS_DIR="$ETC/tls"
BACKUP_DIR="$VAR/backups"
PROJECT=qod
MANAGER_URL="http://127.0.0.1:20900"
NODE_PORT_BASE=21900

# The manager's size (spec section 6.2): 4 CPUs, as 2 failed logins under a connect burst; a 4 GiB
# cap around a 2 GiB heap.
MANAGER_CPUS=4
MANAGER_MEM_GIB=4
MANAGER_HEAP="-Xmx2g"

say()  { printf 'qod-host: %s\n' "$*" >&2; }
die()  { printf 'qod-host: %s\n' "$*" >&2; exit 1; }
need_root() { [[ "$(id -u)" == 0 ]] || die "run as root"; }

# ---------------------------------------------------------------------------------------------
# Sizing (spec section 6.2). Memory in whole GiB, CPUs in whole cores.
# ---------------------------------------------------------------------------------------------

host_cpus() { nproc; }
host_mem_gib() { awk '/^MemTotal:/ { printf "%d", $2 / 1048576 }' /proc/meminfo; }

# The OS and page-cache reserve: max(4 GiB, 10% of RAM, rounded up).
reserve_gib() { local t=$(( ($1 + 9) / 10 )); (( t > 4 )) && echo "$t" || echo 4; }

# Memory left for the workers once the manager and the reserve are taken, from the host's GiB.
worker_pool_gib() { echo $(( $1 - $(reserve_gib "$1") - MANAGER_MEM_GIB )); }

# default_workers MEM_GIB: one per 24 GiB left, plus the spare, at least 2 (one for pools, one spare).
default_workers() {
  local a; a="$(worker_pool_gib "$1")"
  local n=$(( a / 24 + 1 ))
  (( n < 2 )) && n=2
  echo "$n"
}
# Default worker size for N workers: the CPUs left after the manager shared by the N-1 that are not
# spares (a spare runs only maintenance and merges, so its quota is not counted), and the memory
# left shared by all N.
# default_worker_cpus CPUS WORKERS, default_worker_mem MEM_GIB WORKERS
default_worker_cpus() { echo $(( ($1 - MANAGER_CPUS) / ($2 - 1) )); }
default_worker_mem()  { echo $(( $(worker_pool_gib "$1") / $2 )); }

# check_sizing CPUS MEM_GIB WORKERS WORKER_CPUS WORKER_MEM_GIB: prints every rule the sizing breaks,
# one per line, and nothing when it holds.
check_sizing() {
  local c="$1" r="$2" n="$3" wc="$4" wm="$5" reserve
  reserve="$(reserve_gib "$r")"
  (( n >= 2 )) || echo "at least 2 workers: one for pools and one spare for maintenance and merges (got $n)"
  (( wc >= 2 )) || echo "each worker needs at least 2 CPUs, its pool's DuckDB threads being one less (got $wc)"
  (( wm >= 2 )) || echo "each worker needs at least 2 GiB (got $wm)"
  (( wc * 4 >= wm )) || echo "at least 1 vCPU per 4 GiB per worker: $wc CPUs for $wm GiB"
  (( MANAGER_CPUS + (n - 1) * wc <= c )) ||
    echo "the manager's $MANAGER_CPUS CPUs plus $((n - 1)) non-spare workers of $wc CPUs need $((MANAGER_CPUS + (n - 1) * wc)) of this host's $c"
  (( MANAGER_MEM_GIB + n * wm <= r - reserve )) ||
    echo "the manager's ${MANAGER_MEM_GIB} GiB plus $n workers of $wm GiB need $((MANAGER_MEM_GIB + n * wm)) GiB of the $((r - reserve)) left after the ${reserve} GiB reserve of this host's $r GiB"
  return 0
}

# parse_worker_size "4cpu,16g" -> "4 16"
parse_worker_size() {
  [[ "$1" =~ ^([0-9]+)cpu,([0-9]+)g$ ]] || die "--worker-size wants <cpus>cpu,<GiB>g, for example 4cpu,16g (got '$1')"
  echo "${BASH_REMATCH[1]} ${BASH_REMATCH[2]}"
}

# ---------------------------------------------------------------------------------------------
# host.env: what the compose file is rendered from. Plain KEY=VALUE, no secret.
# ---------------------------------------------------------------------------------------------

load_host_env() {
  [[ -r "$HOST_ENV" ]] || die "$HOST_ENV is missing: is this host installed (install.sh)?"
  # shellcheck disable=SC1090
  source "$HOST_ENV"
  : "${QOD_IMAGE:?} ${HOST_NAME:?} ${WORKER_IDS:?} ${WORKER_CPUS:?} ${WORKER_MEM_GIB:?} ${SPILL_ROOT:?} ${POSTGRES:?}"
  DATA_DIR="${DATA_DIR:-}"
}

write_host_env() {
  local tmp="$HOST_ENV.tmp.$$"
  cat > "$tmp" <<EOF
# Written by install.sh and qod-host scale; read by qod-host render. No secret here.
QOD_IMAGE=$QOD_IMAGE
HOST_NAME=$HOST_NAME
WORKER_IDS="$WORKER_IDS"
WORKER_CPUS=$WORKER_CPUS
WORKER_MEM_GIB=$WORKER_MEM_GIB
SPILL_ROOT=$SPILL_ROOT
DATA_DIR=$DATA_DIR
POSTGRES=$POSTGRES
EOF
  chmod 644 "$tmp"
  mv -f "$tmp" "$HOST_ENV"
}

worker_name() { echo "${HOST_NAME}-w$1"; }
worker_port() { echo $(( NODE_PORT_BASE + $1 )); }
worker_service() { echo "worker-$1"; }
worker_container() { echo "qod-worker-$1"; }

# The spill folder of worker I: its own, so that its join can empty it at start (spec section 7.3).
# UID 1000 (the image's quack user), mode 700, under a root-owned SPILL_ROOT.
make_spill_dir() {
  install -d -o root -g root -m 0755 "$SPILL_ROOT"
  install -d -o 1000 -g 1000 -m 0700 "$SPILL_ROOT/w$1"
}

# ---------------------------------------------------------------------------------------------
# The compose file (spec section 9.2), rendered from host.env. No secret: those are in env files.
# ---------------------------------------------------------------------------------------------

render_compose() {
  local tmp="$COMPOSE_FILE.tmp.$$" i data_vol="" pg_dep=""
  [[ -n "$DATA_DIR" ]] && data_vol="      - $DATA_DIR:$DATA_DIR"
  [[ "$POSTGRES" == bundled ]] && pg_dep=$'    depends_on: { postgres: { condition: service_healthy } }'
  {
    cat <<EOF
# Generated by qod-host render from $HOST_ENV: edit that file (or run qod-host scale), not this one.
name: $PROJECT
x-qod: &qod
  image: $QOD_IMAGE:\${QOD_VERSION:?set QOD_VERSION in $VERSION_ENV}
  network_mode: host
  restart: unless-stopped
  init: false
  read_only: true
  security_opt: [no-new-privileges:true]
  cap_drop: [ALL]
  logging: { driver: journald, options: { tag: "{{.Name}}" } }

services:
  manager:
    <<: *qod
    container_name: qod-manager
    command: [manager]
    env_file: [$QOD_ENV]
    mem_limit: ${MANAGER_MEM_GIB}g
    cpus: $MANAGER_CPUS
    stop_grace_period: 75s
    volumes:
      - $VAR/state:/app/state
      - $TLS_DIR:$TLS_DIR:ro
EOF
    [[ -n "$data_vol" ]] && echo "$data_vol"
    cat <<EOF
    tmpfs: ["/tmp:exec"]
    healthcheck: { test: [CMD, qod, healthcheck, manager], interval: 15s, timeout: 5s, retries: 4, start_period: 120s }
EOF
    [[ -n "$pg_dep" ]] && echo "$pg_dep"
    for i in $WORKER_IDS; do
      cat <<EOF

  $(worker_service "$i"):
    <<: *qod
    container_name: $(worker_container "$i")
    command: [worker]
    env_file: [$WORKER_ENV]
    environment: { QOD_FLEET_NAME: $(worker_name "$i"), QOD_FLEET_NODE_PORT: "$(worker_port "$i")" }
    mem_limit: ${WORKER_MEM_GIB}g
    cpus: $WORKER_CPUS
    stop_grace_period: 70s
    depends_on: { manager: { condition: service_healthy } }
    volumes:
      - $SPILL_ROOT/w$i:/duckdb-tmp
EOF
      [[ -n "$data_vol" ]] && echo "$data_vol"
      cat <<EOF
    tmpfs: ["/home/quack/.qod-fleet:mode=0700,uid=1000,gid=1000", /tmp]
    healthcheck: { test: [CMD, qod, healthcheck, worker], interval: 15s, timeout: 5s, retries: 4, start_period: 60s }
EOF
    done
    if [[ "$POSTGRES" == bundled ]]; then
      cat <<EOF

  # Bundled PostgreSQL (decision D2, option B): preview, single host only, an exception to the rule
  # that durable state lives outside the QoD servers. Loopback only; daily dump by qod-backup.timer.
  postgres:
    image: postgres:16
    container_name: qod-postgres
    network_mode: host
    restart: unless-stopped
    security_opt: [no-new-privileges:true]
    logging: { driver: journald, options: { tag: "{{.Name}}" } }
    command: [postgres, -c, listen_addresses=127.0.0.1, -c, max_connections=200]
    env_file: [$PG_ENV]
    volumes:
      - $VAR/pgdata:/var/lib/postgresql/data
    stop_grace_period: 60s
    healthcheck: { test: [CMD-SHELL, pg_isready -h 127.0.0.1 -U postgres], interval: 5s, timeout: 3s, retries: 20 }
EOF
    fi
  } > "$tmp"
  chmod 644 "$tmp"
  mv -f "$tmp" "$COMPOSE_FILE"
}

# verify_image IMAGE:TAG: the image's cosign signature, made keyless by this repository's release or
# snapshot workflow (scripts/release/sign-image.sh). Skipped, with a warning, when cosign is not
# installed; refuses an image with no valid signature. Images built elsewhere (--image) are not
# signed by those workflows: pass --no-verify for them.
SIGNER_ISSUER="https://token.actions.githubusercontent.com"
SIGNER_IDENTITY='^https://github.com/starlake-ai/(qod|quack-on-demand)/\.github/workflows/(release|snapshot)\.yml@'
verify_image() {
  if ! command -v cosign >/dev/null; then
    say "WARN: cosign is not installed: the signature of $1 is not checked (https://docs.sigstore.dev/cosign/system_config/installation/)"
    return 0
  fi
  cosign verify --certificate-oidc-issuer "$SIGNER_ISSUER" --certificate-identity-regexp "$SIGNER_IDENTITY" "$1" >/dev/null 2>&1 ||
    die "$1 has no valid signature from the QoD release workflows (cosign verify); pass --no-verify only for an image you built yourself"
  say "signature of $1 verified"
}

compose() { docker compose --progress quiet -p "$PROJECT" -f "$COMPOSE_FILE" --env-file "$VERSION_ENV" "$@"; }

# ---------------------------------------------------------------------------------------------
# The manager's REST API, with the host's personal access token (host.token, root 600), sent as
# a header read from a file so it never appears in ps.
# ---------------------------------------------------------------------------------------------

api() {
  local method="$1" path="$2" hdr
  [[ -r "$TOKEN_FILE" ]] || die "$TOKEN_FILE is missing: install.sh creates it once the manager is up"
  hdr="$(mktemp)"; chmod 600 "$hdr"
  printf 'X-API-Key: %s\n' "$(cat "$TOKEN_FILE")" > "$hdr"
  if [[ "$method" == GET ]]; then
    curl -fsS -H @"$hdr" "$MANAGER_URL$path"
  else
    curl -fsS -X "$method" -H @"$hdr" -H 'Content-Type: application/json' --data-binary @- "$MANAGER_URL$path"
  fi
  local rc=$?
  rm -f "$hdr"
  return $rc
}

wait_manager_ready() {
  local i
  for i in $(seq 1 "${1:-180}"); do
    curl -fsS -o /dev/null "$MANAGER_URL/ready" 2>/dev/null && return 0
    sleep 1
  done
  return 1
}

# One line per fleet server: name approval liveness unschedulable assigned-node-or-dash pool-or-dash
fleet_servers() {
  api GET /api/fleet/servers | jq -r '.servers[] | [.name, .approval, .liveness, (.unschedulable|tostring), (.assignedNodeId // "-"), ((.tenant // "-") + "/" + (.tenantDb // "-") + "/" + (.pool // "-"))] | @tsv'
}
