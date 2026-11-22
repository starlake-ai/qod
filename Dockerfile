# syntax=docker/dockerfile:1.7

# =========================================================================
#  Stage 1 - Build the uber-jar (UI + Scala)
# =========================================================================
FROM eclipse-temurin:25-jdk AS build

# sbt + node (the project's `sbt assembly` shells out to `npm run build` for
# the React admin console).
RUN <<EOF
set -eu
apt-get update
apt-get install -y --no-install-recommends curl gnupg ca-certificates apt-transport-https
echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" \
  > /etc/apt/sources.list.d/sbt.list
curl -fsSL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x99e82a75642ac823" \
  | gpg --dearmor -o /etc/apt/trusted.gpg.d/sbt.gpg
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt-get update
apt-get install -y --no-install-recommends sbt nodejs
rm -rf /var/lib/apt/lists/*
EOF

WORKDIR /src
# sbt's launcher default heap (1 GB) spends most of an assembly in GC (86% of the time on a
# 12-vCPU VM); 4 GB keeps the build CPU-bound.
ENV SBT_OPTS="-Xmx4g -Xss4m"

# Pre-fetch deps using only the build descriptors - keeps the layer cached
# across source-only changes.
# The node_modules cache is keyed per TARGETARCH: a multi-platform buildx run
# executes the amd64 and arm64 stages concurrently, and `npm ci` starts by
# wiping node_modules - two builds sharing one cache mount race into
# ENOTEMPTY. The contents are arch-specific anyway (esbuild/rollup natives).
ARG TARGETARCH
COPY build.sbt /src/
COPY project/  /src/project/
COPY ui/package.json ui/package-lock.json* /src/ui/
RUN --mount=type=cache,sharing=locked,target=/root/.sbt \
    --mount=type=cache,sharing=locked,target=/root/.ivy2/cache \
    --mount=type=cache,sharing=locked,target=/root/.cache/coursier \
    --mount=type=cache,target=/src/ui/node_modules,id=ui-node-modules-$TARGETARCH \
    sbt -no-colors update && \
    (cd /src/ui && npm ci)

COPY . /src/
# UiBuild.scala skips `npm ci` when ui/node_modules already exists - and the
# cache-mount target above is always an existing (possibly empty) directory,
# so we re-run `npm ci` here to guarantee the tsc/vite binaries are present
# before `sbt assembly` invokes `npm run build`.
RUN --mount=type=cache,sharing=locked,target=/root/.sbt \
    --mount=type=cache,sharing=locked,target=/root/.ivy2/cache \
    --mount=type=cache,sharing=locked,target=/root/.cache/coursier \
    --mount=type=cache,target=/src/ui/node_modules,id=ui-node-modules-$TARGETARCH <<EOF
set -eu
cd /src/ui && npm ci
cd /src && sbt -no-colors assembly
cp distrib/quack-on-demand-assembly-*.jar /quack-on-demand.jar

# Strip the DuckDB JNI natives this image never loads from the assembly jar
# (saves ~170MB): the jar bundles one per platform so it can run anywhere
# (2.0.0.0: libduckdb_java.so_{linux_amd64,linux_arm64,macos_arm64,
# windows_amd64}; the set and its names change between DuckDB releases, so
# they are listed from the jar rather than named here), and only the
# matching Linux one is loaded inside the runtime container.
apt-get update
apt-get install -y --no-install-recommends zip unzip
case "${TARGETARCH:-amd64}" in
  amd64) keep=libduckdb_java.so_linux_amd64 ;;
  arm64) keep=libduckdb_java.so_linux_arm64 ;;
  *)     echo "Unsupported TARGETARCH=${TARGETARCH}" >&2; exit 1 ;;
esac
unzip -Z1 /quack-on-demand.jar | grep -qx "$keep" \
  || { echo "the DuckDB JDBC in the jar has no $keep" >&2; exit 1; }
drop="$(unzip -Z1 /quack-on-demand.jar | grep '^libduckdb_java\.so_' | grep -vx "$keep" || true)"
if [ -n "$drop" ]; then
  # shellcheck disable=SC2086  # one entry name per word
  zip -dq /quack-on-demand.jar $drop
fi
rm -rf /var/lib/apt/lists/*
EOF

# =========================================================================
#  Stage 2 - The qod wheel from this checkout (never a PyPI release that may
#  lag it): `qod fleet join` for the worker role and `qod healthcheck`. Pure
#  Python plus the bundled qod-node binaries, so the builder's interpreter
#  version does not matter.
# =========================================================================
FROM python:3.11-slim-bookworm AS wheel
COPY cli/ /src/cli/
RUN pip wheel --no-deps --wheel-dir /wheels /src/cli

# =========================================================================
#  Stage 3 - Runtime: ONE image for every role (deploy/docker/qod-entrypoint.sh)
#    manager  the manager, on the local runtime (nodes spawned in this
#             container) or the fleet runtime
#    worker   a fleet server, `qod fleet join`, one node per container
# =========================================================================
FROM eclipse-temurin:25-jre

ARG DUCKDB_VERSION=2.0.0
ARG TARGETARCH

# Runtime deps:
#   duckdb              - required by spawn-quack-node.sh to host each Quack node
#   postgresql-client   - psql, bootstraps the catalog DB if missing
#   openssl             - auto-generates the self-signed TLS cert on first boot
#   bash + tini         - script interpreter + PID 1 signal forwarder
#   python3 + venv      - the qod CLI (worker role, `qod healthcheck`)
RUN <<EOF
set -eu
apt-get update
apt-get install -y --no-install-recommends \
  bash tini curl ca-certificates postgresql-client openssl python3 python3-venv

case "${TARGETARCH:-amd64}" in
  amd64) DUCKDB_ARCH=linux-amd64 ;;
  arm64) DUCKDB_ARCH=linux-arm64 ;;
  *)     echo "Unsupported TARGETARCH=${TARGETARCH}" >&2; exit 1 ;;
esac
# The sha256 of each DuckDB release asset this image downloads, one line per <version>/<asset>.
# They must equal _DUCKDB_SHA256 in cli/src/qod_cli/launcher.py (scripts/release/release_manifest.py
# --check-pins refuses a difference), and a DUCKDB_VERSION with no line here fails the build.
duckdb_sha256() {
  case "$1" in
    2.0.0/duckdb-cli-linux-amd64.tar.gz) echo c6507206baf7793c6013dbd82368469a45f20dc0e17a6f92f204602f652ed2bc ;;
    2.0.0/duckdb-cli-linux-arm64.tar.gz) echo 859e1875d2a9515fbb4bf8871061437a14af82320da68c753f094c4b776348fe ;;
    2.0.0/duckdb-shared-libs-linux-amd64.tar.gz) echo 993ad89e7dfca66cd6da994c2cad090c2fc160ddf9f550498a098ab4bb13cfb1 ;;
    2.0.0/duckdb-shared-libs-linux-arm64.tar.gz) echo c60eecf91143ca6bbac55c4f4d40dc22520488b0ac257c86e0fe9a2b451cf2b7 ;;
  esac
}
# Downloads one release asset to a file and checks its sha256 before anything unpacks it.
fetch_duckdb() {
  digest="$(duckdb_sha256 "${DUCKDB_VERSION}/$1")"
  if [ -z "$digest" ]; then
    echo "no pinned sha256 for $1 of DuckDB ${DUCKDB_VERSION}: add it to duckdb_sha256 in this Dockerfile and to _DUCKDB_SHA256 in cli/src/qod_cli/launcher.py" >&2
    exit 1
  fi
  curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 "https://github.com/duckdb/duckdb/releases/download/v${DUCKDB_VERSION}/$1" \
    -o "$2"
  echo "$digest  $2" | sha256sum -c -
}
# DuckDB 2.x release assets are flat .tar.gz archives (duckdb-cli-*, duckdb-shared-libs-*).
fetch_duckdb "duckdb-cli-${DUCKDB_ARCH}.tar.gz" /tmp/duckdb-cli.tar.gz
tar -xzf /tmp/duckdb-cli.tar.gz -C /usr/local/bin duckdb
chmod +x /usr/local/bin/duckdb
rm /tmp/duckdb-cli.tar.gz

# libduckdb.so is required at runtime by libquackwire (the JNI shim that is
# the manager's only client to its nodes), which links the pinned libduckdb
# dynamically (DuckDB::LibraryVersion / ArrowConverter / etc.). The duckdb
# CLI above is self-contained and does not provide a system-installed
# libduckdb.so.
fetch_duckdb "duckdb-shared-libs-${DUCKDB_ARCH}.tar.gz" /tmp/libduckdb.tar.gz
mkdir -p /tmp/libduckdb
tar -xzf /tmp/libduckdb.tar.gz -C /tmp/libduckdb
install -m 0755 /tmp/libduckdb/libduckdb.so /usr/local/lib/libduckdb.so
ldconfig
rm -rf /tmp/libduckdb.tar.gz /tmp/libduckdb

apt-get purge -y --auto-remove curl
rm -rf /var/lib/apt/lists/*
EOF

# Non-root runtime user. Newer Ubuntu base images (24.04+) ship with an
# `ubuntu` user already at UID 1000 - remove it first so this useradd
# can claim the same UID.
RUN if id -u 1000 >/dev/null 2>&1; then userdel -r "$(id -un 1000)" 2>/dev/null || true; fi && \
    useradd --create-home --shell /bin/bash --uid 1000 quack

# The qod CLI, without the dependencies only `qod sql` needs (pyarrow and the ADBC driver, about
# 200 MB; `qod sql` names them when they are missing). Then qod-node moves out of the wheel to
# /opt/qod-node, installed once here so that neither `qod fleet join` nor the manager writes a
# binary at run time (a read-only root filesystem): bin/qod-node beside a lib link to the image's
# libduckdb, the layout its `../lib` rpath reads. The other platforms' binaries go.
COPY --from=wheel /wheels /tmp/wheels
RUN <<EOF
set -eu
python3 -m venv /opt/qod
# --no-warn-conflicts: pip would report the missing pyarrow and ADBC driver, left out on purpose.
/opt/qod/bin/pip install --no-cache-dir --no-warn-conflicts --no-deps /tmp/wheels/*.whl
/opt/qod/bin/pip install --no-cache-dir --no-warn-conflicts typer rich httpx platformdirs tomli-w pg8000
rm -rf /tmp/wheels
case "${TARGETARCH:-amd64}" in
  amd64) plat=linux-x86_64 ;;
  arm64) plat=linux-aarch64 ;;
  *)     echo "Unsupported TARGETARCH=${TARGETARCH}" >&2; exit 1 ;;
esac
bundle="$(/opt/qod/bin/python -c 'import qod_cli, pathlib; print(pathlib.Path(qod_cli.__file__).parent / "qod-node")')"
install -d /opt/qod-node/bin
install -m 0755 "$bundle/$plat/qod-node" /opt/qod-node/bin/qod-node
ln -s /usr/local/lib /opt/qod-node/lib
rm -rf "$bundle"/*/
/opt/qod-node/bin/qod-node --build-info
/opt/qod/bin/qod --help >/dev/null
EOF

WORKDIR /app
# Use --chown on COPY rather than a trailing `chown -R`. `chown -R` rewrites
# every file into a new layer (~213MB doubled the assembly jar); --chown is
# applied as the file is staged, so no extra layer is produced. Same for
# --chmod on the scripts.
COPY --from=build --chown=quack:quack /quack-on-demand.jar           /app/quack-on-demand.jar
COPY --chown=quack:quack --chmod=0755 scripts/spawn-quack-node.sh    /app/scripts/spawn-quack-node.sh
COPY --chown=quack:quack --chmod=0755 scripts/_load-common.sh        /app/scripts/_load-common.sh
COPY --chown=quack:quack --chmod=0755 scripts/load-tpch-dbgen.sh     /app/scripts/load-tpch-dbgen.sh
COPY --chown=quack:quack --chmod=0755 scripts/load-tpcds-dbgen.sh    /app/scripts/load-tpcds-dbgen.sh
COPY --chown=quack:quack --chmod=0755 scripts/load-ssb-dbgen.sh      /app/scripts/load-ssb-dbgen.sh
COPY --chmod=0755 deploy/docker/qod-entrypoint.sh                   /usr/local/bin/qod-entrypoint
RUN install -d -o quack -g quack /app/certs /app/state /app/ducklake

USER quack

# Every extension a node or the manager's own DuckDB loads, installed as `quack` into its
# ~/.duckdb (owned by it, no `chown -R` layer), so nothing is downloaded at run time: under a
# read-only root filesystem or with no outbound network a download fails, and a hanging one keeps
# every node from starting. Nodes: ducklake, postgres, quack, httpfs, aws, and avro, which the spawn
# script INSTALLs at every node start. The manager's DuckLake initializer: ducklake,
# postgres_scanner (the same file as postgres) and httpfs. One statement per line: the CLI skips
# the rest of a line after a failing statement. The check below fails the build on a missing one.
RUN <<EOF
set -eu
duckdb <<'SQL'
INSTALL ducklake;
INSTALL postgres;
INSTALL quack;
INSTALL httpfs;
INSTALL aws;
INSTALL avro;
SQL
installed="$(duckdb -noheader -list -c "SELECT extension_name FROM duckdb_extensions() WHERE installed AND install_mode <> 'STATICALLY_LINKED' ORDER BY 1")"
for e in avro aws ducklake httpfs postgres_scanner quack; do
  echo "$installed" | grep -qx "$e" || { echo "extension $e is not installed" >&2; exit 1; }
done
EOF

# Manager REST + UI (20900, also the read-only REST data edge under /data/ when
# QOD_DATA_EDGE_ENABLED=true), the node verdict listener (20901), FlightSQL edge
# (31338) and the native Quack protocol front door (9494, what a DuckDB client
# ATTACHes to).
# Local-mode Quack nodes lease ports from QOD_MIN_PORT..QOD_MAX_PORT;
# expose the default range so a host port-forward can reach them too.
EXPOSE 20900 20901 31338 9494 21900-22500

# Sensible container defaults. Override at run time via -e.
# QOD_NODE_BIN and QOD_SPAWN_SCRIPT point the manager (local runtime) and `qod fleet join` (worker
# role) at the qod-node and the spawn script installed above, so neither unpacks a copy at run time.
ENV QOD_ON_DEMAND_HOST=0.0.0.0 \
    PROXY_HOST=0.0.0.0 \
    QOD_DUCKLAKE_DATA_PATH=/app/ducklake/tpch \
    QOD_STATE_PATH=/app/state/quack-on-demand-state.json \
    PROXY_TLS_CERT_CHAIN=/app/certs/server-cert.pem \
    PROXY_TLS_PRIVATE_KEY=/app/certs/server-key.pem \
    QOD_NODE_BIN=/opt/qod-node/bin/qod-node \
    QOD_SPAWN_SCRIPT=/app/scripts/spawn-quack-node.sh \
    PATH=/opt/qod/bin:$PATH \
    JAVA_OPTS=""

# The role is the first argument (deploy/docker/qod-entrypoint.sh): none or `manager` runs the
# manager, `worker` a fleet server, anything else goes to the jar. tini forwards docker stop's
# SIGTERM: the JVM drains, `qod fleet join` stops its node before exiting.
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/qod-entrypoint"]
