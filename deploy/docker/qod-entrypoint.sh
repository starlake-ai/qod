#!/usr/bin/env bash
# The image's entrypoint (tini runs it as PID 1's child). One image, three roles, chosen by the
# first argument:
#
#   (none) | manager   the manager, `java ... -jar /app/quack-on-demand.jar`
#   worker             a fleet server: `qod fleet join`, one node per container
#   anything else      passed to the jar, so `docker run starlakeai/quack-on-demand demo` works
#
# The manager always runs with -XX:+ExitOnOutOfMemoryError ahead of $JAVA_OPTS, so a heap OOM ends
# the JVM and the container's restart policy applies. It is not a JAVA_OPTS default, which any
# JAVA_OPTS (the compose file's `${JAVA_OPTS:-}`, an env file's -Xmx2g) would replace.
set -euo pipefail

java_cmd() {
  # --sun-misc-unsafe-memory-access=allow keeps JDK 25 from warning at every boot about the
  # sun.misc.Unsafe use of Scala's lazy vals, Arrow and Netty. -Darrow.allocation.manager.type=Unsafe
  # pins Arrow to arrow-memory-unsafe: without it Arrow picks netty and crashes with
  # NoSuchFieldError: chunkSize. Add-Opens is in the jar's manifest.
  # shellcheck disable=SC2086  # JAVA_OPTS is a list of flags, split on purpose
  exec java -XX:+ExitOnOutOfMemoryError --sun-misc-unsafe-memory-access=allow \
    -Darrow.allocation.manager.type=Unsafe ${JAVA_OPTS:-} -jar /app/quack-on-demand.jar "$@"
}

worker() {
  # A container's defaults are wrong for both identity and address: the hostname is the container
  # id (a new server on every recreate) and the first IPv4 is the bridge address (unreachable from
  # the manager), so both must be set explicitly.
  : "${QOD_FLEET_NAME:?QOD_FLEET_NAME is required: a stable server name (the container hostname changes on every recreate)}"
  : "${QOD_FLEET_ADVERTISE_HOST:?QOD_FLEET_ADVERTISE_HOST is required: the host address the manager dials (not the container bridge IP)}"
  : "${QOD_MANAGER_URL:?QOD_MANAGER_URL is required, e.g. http://mgr.internal:20900}"
  : "${QOD_FLEET_JOIN_TOKEN:?QOD_FLEET_JOIN_TOKEN is required}"
  local args=(fleet join --bind-host "${QOD_FLEET_BIND_HOST:-0.0.0.0}" --state-dir "$HOME/.qod-fleet")
  if [[ "${QOD_FLEET_INSECURE:-false}" == "true" ]]; then
    args+=(--insecure)
  fi
  exec qod "${args[@]}" "$@"
}

case "${1:-manager}" in
  manager) shift || true; java_cmd "$@" ;;
  worker)  shift; worker "$@" ;;
  *)       java_cmd "$@" ;;
esac
