#!/usr/bin/env bash
# install.sh: installs QoD on this host as containers, the manager and its fleet workers from one
# image (README.md). Run as root from this folder, at the release tag of --version.
#
#   install.sh --version VERSION --settings FILE [--workers N] [--worker-size <cpus>cpu,<GiB>g]
#              [--postgres external|bundled] [--data-dir DIR] [--spill-root DIR]
#              [--image REPO] [--name NAME] [--no-pull] [--no-verify] [--skip-smoke]
#
# --settings FILE  the manager's own settings, KEY=VALUE lines: QOD_PG_* for an external
#                  PostgreSQL, QOD_DUCKLAKE_DATA_PATH and QOD_S3_* for object storage, any other
#                  QOD_* or PROXY_*. Copied into /etc/qod/qod.env (root, 600); the lines this
#                  script owns (fleet, bind addresses, TLS, the generated secrets) are written
#                  after them and win.
# --postgres       external (default): the customer's PostgreSQL 16+ named in --settings.
#                  bundled: a postgres:16 container on 127.0.0.1:5432, preview, single host only.
# --data-dir DIR   a filesystem data path (decision D1) instead of object storage: DIR is mounted
#                  at the same path into the manager and every worker. No managed storage, and no
#                  second host until it moves to S3.
# --spill-root     default /fastdisk/qod-spill: one folder per worker under it, mounted at
#                  /duckdb-tmp. Its parent must exist (the fast disk's mount point).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

VERSION="" SETTINGS="" WORKERS="" WORKER_SIZE="" POSTGRES=external DATA_DIR="" SPILL_ROOT=/fastdisk/qod-spill
QOD_IMAGE=starlakeai/quack-on-demand HOST_NAME="$(hostname -s)" PULL=1 SMOKE=1 VERIFY=1
while (( $# )); do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --settings) SETTINGS="$2"; shift 2 ;;
    --workers) WORKERS="$2"; shift 2 ;;
    --worker-size) WORKER_SIZE="$2"; shift 2 ;;
    --postgres) POSTGRES="$2"; shift 2 ;;
    --data-dir) DATA_DIR="$2"; shift 2 ;;
    --spill-root) SPILL_ROOT="$2"; shift 2 ;;
    --image) QOD_IMAGE="$2"; shift 2 ;;
    --name) HOST_NAME="$2"; shift 2 ;;
    --no-pull) PULL=0; shift ;;
    --no-verify) VERIFY=0; shift ;;
    --skip-smoke) SMOKE=0; shift ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) die "unknown argument '$1' (install.sh --help)" ;;
  esac
done

need_root
[[ -n "$VERSION" ]] || die "--version is required: the image tag the manager and every worker run"
[[ -n "$SETTINGS" && -r "$SETTINGS" ]] || die "--settings FILE is required and must be readable"
[[ "$POSTGRES" == external || "$POSTGRES" == bundled ]] || die "--postgres is external or bundled"
[[ "$HOST_NAME" =~ ^[a-z0-9][a-z0-9-]{0,40}$ ]] || die "--name must be lowercase letters, digits and '-' (got '$HOST_NAME')"
[[ -z "$DATA_DIR" || "$DATA_DIR" == /* ]] || die "--data-dir must be an absolute path"
[[ "$SPILL_ROOT" == /* ]] || die "--spill-root must be an absolute path"
[[ ! -e "$COMPOSE_FILE" ]] || die "$COMPOSE_FILE exists: this host is installed. Use switch.sh to change version, qod-host scale to resize, qod-host uninstall to start over"

setting() { grep -E "^$1=" "$SETTINGS" | tail -1 | cut -d= -f2- || true; }

# ---------------------------------------------------------------------------------------------
# 1. Preflight
# ---------------------------------------------------------------------------------------------
problems=()
for cmd in docker curl jq openssl ss; do command -v "$cmd" >/dev/null || problems+=("$cmd is not installed"); done
if command -v docker >/dev/null; then
  dv="$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo 0)"
  (( ${dv%%.*} >= 24 )) || problems+=("Docker Engine 24 or later is required (got ${dv:-none})")
  cv="$(docker compose version --short 2>/dev/null || echo 0)"
  (( ${cv%%.*} >= 2 )) || problems+=("docker compose v2 is required (got ${cv:-none})")
fi
[[ "$(stat -fc %T /sys/fs/cgroup 2>/dev/null)" == cgroup2fs ]] || problems+=("cgroup v2 is required: the workers report their cgroup limits as capacity")

CPUS="$(host_cpus)" MEM="$(host_mem_gib)"
[[ -n "$WORKERS" ]] || WORKERS="$(default_workers "$MEM")"
if [[ -n "$WORKER_SIZE" ]]; then
  read -r WORKER_CPUS WORKER_MEM_GIB <<<"$(parse_worker_size "$WORKER_SIZE")"
else
  WORKER_CPUS="$(default_worker_cpus "$CPUS" "$WORKERS")"
  WORKER_MEM_GIB="$(default_worker_mem "$MEM" "$WORKERS")"
fi
while IFS= read -r p; do [[ -n "$p" ]] && problems+=("sizing: $p"); done < <(check_sizing "$CPUS" "$MEM" "$WORKERS" "$WORKER_CPUS" "$WORKER_MEM_GIB")

ports=(20900 20901 31338 9494)
for i in $(seq 1 "$WORKERS"); do ports+=("$(worker_port "$i")"); done
[[ "$POSTGRES" == bundled ]] && ports+=(5432)
for p in "${ports[@]}"; do
  [[ -z "$(ss -Hltn "sport = :$p" 2>/dev/null)" ]] || problems+=("port $p is in use")
done

spill_parent="$(dirname "$SPILL_ROOT")"
[[ -d "$spill_parent" ]] || problems+=("$spill_parent does not exist: mount the spill disk there, or pass --spill-root")
if [[ -d "$spill_parent" ]] && ! mountpoint -q "$spill_parent"; then
  say "WARN: $spill_parent is not a mount point: spills go to the root filesystem"
fi

tcp_open() { timeout 5 bash -c "exec 3<>/dev/tcp/$1/$2" 2>/dev/null; }
if [[ "$POSTGRES" == external ]]; then
  pg_host="$(setting QOD_PG_HOST)" pg_port="$(setting QOD_PG_PORT)"
  [[ -n "$pg_host" && -n "$(setting QOD_PG_USER)" && -n "$(setting QOD_PG_PASSWORD)" ]] ||
    problems+=("--postgres external needs QOD_PG_HOST, QOD_PG_USER and QOD_PG_PASSWORD in $SETTINGS")
  [[ -z "$pg_host" ]] || tcp_open "$pg_host" "${pg_port:-5432}" || problems+=("PostgreSQL at $pg_host:${pg_port:-5432} is not reachable")
else
  for k in QOD_PG_HOST QOD_PG_USER QOD_PG_PASSWORD; do
    [[ -z "$(setting "$k")" ]] || problems+=("--postgres bundled sets $k itself: remove it from $SETTINGS")
  done
fi

data_path="$(setting QOD_DUCKLAKE_DATA_PATH)"
if [[ -n "$DATA_DIR" ]]; then
  [[ -z "$data_path" ]] || problems+=("--data-dir and QOD_DUCKLAKE_DATA_PATH in $SETTINGS are exclusive")
  [[ "$(setting QOD_MANAGED_STORE_ENABLED)" != true ]] || problems+=("managed storage is S3 only: it cannot go with --data-dir")
else
  [[ -n "$data_path" ]] || problems+=("set QOD_DUCKLAKE_DATA_PATH (s3://...) in $SETTINGS, or pass --data-dir for a filesystem data path")
  s3_ep="$(setting QOD_S3_ENDPOINT)"
  if [[ -n "$s3_ep" ]]; then
    ep="${s3_ep#*://}"; ep="${ep%%/*}"; port="${ep##*:}"; hostpart="${ep%:*}"
    [[ "$port" == "$ep" ]] && { [[ "$s3_ep" == http://* ]] && port=80 || port=443; hostpart="$ep"; }
    tcp_open "$hostpart" "$port" || problems+=("the object store at $s3_ep is not reachable")
  fi
fi

say "this host: $CPUS vCPU, $MEM GiB. Manager: $MANAGER_CPUS CPU, ${MANAGER_MEM_GIB} GiB. Workers: $WORKERS x ($WORKER_CPUS CPU, ${WORKER_MEM_GIB} GiB), one of them spare; pools on them run DuckDB with threads=$((WORKER_CPUS - 1))"
if (( ${#problems[@]} )); then
  for p in "${problems[@]}"; do say "refused: $p"; done
  exit 1
fi

# ---------------------------------------------------------------------------------------------
# 2. The image
# ---------------------------------------------------------------------------------------------
if (( PULL )); then
  docker pull -q "$QOD_IMAGE:$VERSION" >/dev/null
  [[ "$POSTGRES" == bundled ]] && docker pull -q postgres:16 >/dev/null
fi
docker image inspect "$QOD_IMAGE:$VERSION" >/dev/null 2>&1 || die "image $QOD_IMAGE:$VERSION is not on this host"
if (( VERIFY )); then verify_image "$QOD_IMAGE:$VERSION"; else say "WARN: --no-verify: the signature of $QOD_IMAGE:$VERSION is not checked"; fi

# ---------------------------------------------------------------------------------------------
# 3. Directories (spec sections 7 and 9.1)
# ---------------------------------------------------------------------------------------------
umask 077
install -d -o root -g root -m 0755 "$ETC" "$VAR" "$HOST_DIR"
install -d -o root -g root -m 0700 "$BACKUP_DIR"
install -d -o 1000 -g 1000 -m 0700 "$VAR/state" "$TLS_DIR"
[[ -n "$DATA_DIR" ]] && install -d -o 1000 -g 1000 -m 0700 "$DATA_DIR"
[[ "$POSTGRES" == bundled ]] && install -d -m 0700 "$VAR/pgdata"
WORKER_IDS="$(seq -s ' ' 1 "$WORKERS")"
for i in $WORKER_IDS; do make_spill_dir "$i"; done

# ---------------------------------------------------------------------------------------------
# 4. Secrets and settings, generated here and written only to root-owned files
# ---------------------------------------------------------------------------------------------
rnd() { openssl rand -hex "$1"; }
JOIN_TOKEN="$(rnd 24)"
owned='^(QOD_RUNTIME_TYPE|QOD_FLEET_JOIN_TOKEN|QOD_ON_DEMAND_HOST|QOD_INTERNAL_URL|QOD_INTERNAL_PORT|QOD_FLEET_AUTO_APPROVE|QOD_FLEET_EPHEMERAL|PROXY_TLS_CERT_CHAIN|PROXY_TLS_PRIVATE_KEY|QOD_QUACK_TLS_ENABLED|QOD_QUACK_TLS_CERT_CHAIN|QOD_QUACK_TLS_PRIVATE_KEY)='
{
  echo "# The manager's settings. Root, 600. The block after '# install.sh' is this host's own."
  grep -v -E "$owned" "$SETTINGS" | grep -v -E '^\s*#' | grep -E '^[A-Z_][A-Z0-9_]*=' || true
  echo "# install.sh"
  echo "QOD_RUNTIME_TYPE=fleet"
  echo "QOD_FLEET_JOIN_TOKEN=$JOIN_TOKEN"
  echo "QOD_ON_DEMAND_HOST=127.0.0.1"
  echo "QOD_INTERNAL_URL=http://127.0.0.1:20901"
  echo "QOD_FLEET_AUTO_APPROVE=127.0.0.1/32"
  echo "PROXY_TLS_CERT_CHAIN=$TLS_DIR/server-cert.pem"
  echo "PROXY_TLS_PRIVATE_KEY=$TLS_DIR/server-key.pem"
  echo "QOD_QUACK_TLS_ENABLED=true"
  # Named again for the front door: its defaults are the relative certs/ paths, not PROXY_TLS_*,
  # and it cannot write a certificate of its own on a read-only filesystem.
  echo "QOD_QUACK_TLS_CERT_CHAIN=$TLS_DIR/server-cert.pem"
  echo "QOD_QUACK_TLS_PRIVATE_KEY=$TLS_DIR/server-key.pem"
  [[ -n "$(setting JAVA_OPTS)" ]] || echo "JAVA_OPTS=$MANAGER_HEAP"
  # Used at the first boot only, then hashed in PostgreSQL: change it after install, then remove it.
  [[ -n "$(setting QOD_ADMIN_PASSWORD)" ]] || echo "QOD_ADMIN_PASSWORD=$(rnd 16)"
  if [[ -n "$DATA_DIR" ]]; then echo "QOD_DUCKLAKE_DATA_PATH=$DATA_DIR/data"; fi
  if [[ "$POSTGRES" == bundled ]]; then
    echo "QOD_PG_HOST=127.0.0.1"
    echo "QOD_PG_PORT=5432"
    echo "QOD_PG_USER=qod"
    echo "QOD_PG_PASSWORD=$(rnd 16)"
    echo "QOD_PG_DBNAME=qod"
  fi
} > "$QOD_ENV"
chmod 600 "$QOD_ENV"

cat > "$WORKER_ENV" <<EOF
# Shared by every worker. Root, 600. No PostgreSQL password and no storage key: a node gets those
# in its assignment from the manager.
QOD_MANAGER_URL=$MANAGER_URL
QOD_FLEET_INSECURE=true
QOD_FLEET_JOIN_TOKEN=$JOIN_TOKEN
QOD_FLEET_ADVERTISE_HOST=127.0.0.1
QOD_FLEET_BIND_HOST=127.0.0.1
QOD_FLEET_SPILL_DIR=/duckdb-tmp
EOF
chmod 600 "$WORKER_ENV"

if [[ "$POSTGRES" == bundled ]]; then
  printf 'POSTGRES_PASSWORD=%s\n' "$(rnd 16)" > "$PG_ENV"
  chmod 600 "$PG_ENV"
fi

printf 'QOD_VERSION=%s\n' "$VERSION" > "$VERSION_ENV"
chmod 644 "$VERSION_ENV"
write_host_env

# TLS for Flight SQL and Quack: the customer's certificate when $TLS_DIR holds one (Caddy's hook
# copies it there), else a self-signed one for this host, so the manager boots on a read-only
# filesystem. UID 1000, key 600, PKCS8.
if [[ ! -s "$TLS_DIR/server-key.pem" ]]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 825 -subj "/CN=$(hostname -f 2>/dev/null || hostname)" \
    -addext "subjectAltName=DNS:$(hostname -f 2>/dev/null || hostname),DNS:localhost,IP:127.0.0.1" \
    -keyout "$TLS_DIR/server-key.pem" -out "$TLS_DIR/server-cert.pem" >/dev/null 2>&1
  say "TLS: generated a self-signed certificate in $TLS_DIR; replace it with the host's own"
fi
chown 1000:1000 "$TLS_DIR"/server-*.pem
chmod 600 "$TLS_DIR/server-key.pem"; chmod 644 "$TLS_DIR/server-cert.pem"

# ---------------------------------------------------------------------------------------------
# 5. Scripts, units, compose file, start
# ---------------------------------------------------------------------------------------------
for f in lib.sh qod-host switch.sh smoke-test.sh README.md; do install -m 0755 "$HERE/$f" "$HOST_DIR/$f"; done
chmod 644 "$HOST_DIR/lib.sh" "$HOST_DIR/README.md"
ln -sfn "$HOST_DIR/qod-host" /usr/local/sbin/qod-host
units=(qod.service qod-health.service qod-health.timer)
[[ "$POSTGRES" == bundled ]] && units+=(qod-backup.service qod-backup.timer)
for u in "${units[@]}"; do install -m 0644 "$HERE/$u" "$UNIT_DIR/$u"; done
render_compose
systemctl daemon-reload

if [[ "$POSTGRES" == bundled ]]; then
  compose up -d --wait postgres
  # The qod role and database, with the password of qod.env, sent on stdin.
  pg_pw="$(grep -E '^QOD_PG_PASSWORD=' "$QOD_ENV" | cut -d= -f2-)"
  docker exec -i qod-postgres psql -q -v ON_ERROR_STOP=1 -U postgres -h 127.0.0.1 >/dev/null <<SQL
SELECT 'CREATE ROLE qod LOGIN CREATEDB' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'qod')\gexec
ALTER ROLE qod PASSWORD '$pg_pw';
SELECT 'CREATE DATABASE qod OWNER qod' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'qod')\gexec
SQL
  unset pg_pw
fi

timers=(qod-health.timer)
[[ "$POSTGRES" == bundled ]] && timers+=(qod-backup.timer)
say "starting qod.service (the manager, then $WORKERS workers)"
systemctl enable --now qod.service "${timers[@]}"

# ---------------------------------------------------------------------------------------------
# 6. The host's token, then every worker approved and heartbeating
# ---------------------------------------------------------------------------------------------
wait_manager_ready 300 || die "the manager is not ready: journalctl -t qod-manager"
admin_pw="$(grep -E '^QOD_ADMIN_PASSWORD=' "$QOD_ENV" | tail -1 | cut -d= -f2-)"
session="$(printf '%s' "$admin_pw" | jq -Rs '{username: "admin", password: .}' |
  curl -fsS -H 'Content-Type: application/json' --data-binary @- "$MANAGER_URL/api/auth/login" | jq -r .token)"
unset admin_pw
[[ -n "$session" && "$session" != null ]] || die "could not sign in as admin with QOD_ADMIN_PASSWORD of $QOD_ENV"
hdr="$(mktemp)"; printf 'X-API-Key: %s\n' "$session" > "$hdr"
jq -n --arg n "qod-host $HOST_NAME" '{name: $n}' |
  curl -fsS -H @"$hdr" -H 'Content-Type: application/json' --data-binary @- "$MANAGER_URL/api/auth/pat/create" |
  jq -r .token > "$TOKEN_FILE"
rm -f "$hdr"; unset session
chmod 600 "$TOKEN_FILE"
[[ -s "$TOKEN_FILE" ]] || die "could not create the host's access token"

say "waiting for $WORKERS workers to be approved and heartbeating"
for _ in $(seq 1 120); do
  ready=0
  while IFS=$'\t' read -r name approval liveness _rest; do
    [[ "$name" == "$HOST_NAME"-w* && "$approval" == approved && "$liveness" == reachable ]] && ready=$((ready + 1))
  done < <(fleet_servers 2>/dev/null || true)
  (( ready >= WORKERS )) && break
  sleep 2
done
(( ready >= WORKERS )) || die "only $ready of $WORKERS workers are approved and reachable: qod-host status"

# ---------------------------------------------------------------------------------------------
# 7. Smoke test
# ---------------------------------------------------------------------------------------------
if (( SMOKE )); then
  "$HOST_DIR/smoke-test.sh" --manager
  "$HOST_DIR/smoke-test.sh" --queries
fi

say "installed QoD $VERSION: qod-host status. The admin password is QOD_ADMIN_PASSWORD in $QOD_ENV: change it (qod auth change-password), then remove that line."
