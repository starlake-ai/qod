#!/usr/bin/env bash
# switch.sh VERSION [--restore-backup FILE] [--no-pull]: runs another release on this host (README.md).
#
# Upgrade and rollback are the same command. Before switching it dumps the control plane
# (qod-host backup) and reads the release manifest (release/upgrades.json in the jar):
#   - an upgrade from a release older than the target's min_from_version is refused;
#   - a rollback across a one-way release (rollback_without_restore false) is refused, as the
#     older manager would refuse to start on the newer schema and crash-loop, unless
#     --restore-backup FILE restores a control-plane dump taken before the upgrade (a qod-*.dump of
#     /var/lib/qod/backups). Only the control-plane database is restored: a DuckLake catalog an
#     upgrade migrated stays migrated.
# On a single host every switch is a short outage: statements drain for up to 60 s, then every
# container is recreated.
set -euo pipefail
HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

TARGET="" RESTORE="" PULL=1
while (( $# )); do
  case "$1" in
    --restore-backup) RESTORE="$2"; shift 2 ;;
    --no-pull) PULL=0; shift ;;
    -h|--help) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) die "unknown argument '$1' (switch.sh --help)" ;;
    *) TARGET="$1"; shift ;;
  esac
done
need_root
[[ -n "$TARGET" ]] || die "usage: switch.sh VERSION [--restore-backup FILE]"
load_host_env
CURRENT="$(grep -E '^QOD_VERSION=' "$VERSION_ENV" | cut -d= -f2-)"
[[ "$TARGET" != "$CURRENT" ]] || die "QoD $CURRENT already runs here"
[[ -z "$RESTORE" || -r "$RESTORE" ]] || die "cannot read $RESTORE"

if (( PULL )); then docker pull -q "$QOD_IMAGE:$TARGET" >/dev/null; fi
docker image inspect "$QOD_IMAGE:$TARGET" >/dev/null 2>&1 || die "image $QOD_IMAGE:$TARGET is not on this host"

manifest() {
  docker run --rm --network none --entrypoint python3 "$QOD_IMAGE:$1" -c \
    'import zipfile, sys; sys.stdout.write(zipfile.ZipFile("/app/quack-on-demand.jar").read("release/upgrades.json").decode())'
}
plain() { echo "${1%%-*}"; }                   # 0.14.2-SNAPSHOT -> 0.14.2
older() { [[ "$(printf '%s\n%s\n' "$(plain "$1")" "$(plain "$2")" | sort -V | head -1)" == "$(plain "$1")" && "$(plain "$1")" != "$(plain "$2")" ]]; }

if older "$TARGET" "$CURRENT"; then
  # Rollback: the releases after TARGET up to CURRENT, as the running release's manifest lists them.
  blocked="$(manifest "$CURRENT" | jq -r --arg t "$(plain "$TARGET")" --arg c "$(plain "$CURRENT")" '
    def v: split(".") | map(tonumber);
    .releases[] | select((.version | v) > ($t | v) and (.version | v) <= ($c | v) and (.rollback_without_restore | not))
    | "\(.version): \((.rollback_blocked_by // []) | join("; "))"')"
  if [[ -n "$blocked" && -z "$RESTORE" ]]; then
    say "refused: QoD $TARGET cannot run on the schema $CURRENT left, as these releases changed it one way:"
    while IFS= read -r l; do say "  $l"; done <<<"$blocked"
    say "restore the control-plane dump taken before the upgrade: switch.sh $TARGET --restore-backup FILE. Dumps:"
    # shellcheck disable=SC2012
    ls -1t "$BACKUP_DIR"/qod-2*.dump 2>/dev/null | head -5 | sed 's/^/qod-host:   /' >&2 || true
    exit 1
  fi
else
  min_from="$(manifest "$TARGET" | jq -r --arg t "$(plain "$TARGET")" '.releases[] | select(.version == $t) | .min_from_version // empty')"
  if [[ -n "$min_from" ]] && older "$CURRENT" "$min_from"; then
    die "refused: QoD $TARGET upgrades from $min_from or later; switch to $min_from first (this host runs $CURRENT)"
  fi
fi

if [[ -n "$RESTORE" ]]; then
  [[ -t 0 ]] || die "--restore-backup replaces the control-plane database: run it from a terminal"
  printf 'This replaces the control-plane database with %s. Type the host name (%s) to confirm: ' "$RESTORE" "$HOST_NAME" >&2
  read -r answer
  [[ "$answer" == "$HOST_NAME" ]] || die "not confirmed; nothing was changed"
fi

"$HERE/qod-host" backup
say "switching $CURRENT -> $TARGET: running statements drain for up to 60 s, then every container restarts"
compose stop >/dev/null

if [[ -n "$RESTORE" ]]; then
  if [[ "$POSTGRES" == bundled ]]; then
    compose up -d --wait postgres >/dev/null
    docker exec -i qod-postgres pg_restore --clean --if-exists --no-owner --role=qod -U postgres -h 127.0.0.1 -d qod < "$RESTORE"
  else
    envf="$(mktemp)"
    awk '/^QOD_PG_HOST=/ {print "PGHOST=" substr($0, index($0,"=")+1)}
         /^QOD_PG_PORT=/ {print "PGPORT=" substr($0, index($0,"=")+1)}
         /^QOD_PG_USER=/ {print "PGUSER=" substr($0, index($0,"=")+1)}
         /^QOD_PG_PASSWORD=/ {print "PGPASSWORD=" substr($0, index($0,"=")+1)}
         /^QOD_PG_DBNAME=/ {print "PGDATABASE=" substr($0, index($0,"=")+1)}' "$QOD_ENV" > "$envf"
    grep -q '^PGDATABASE=' "$envf" || echo PGDATABASE=qod >> "$envf"
    docker run --rm -i --network host --env-file "$envf" --entrypoint sh "$QOD_IMAGE:$TARGET" \
      -c 'pg_restore --clean --if-exists --no-owner -d "$PGDATABASE"' < "$RESTORE" || { rm -f "$envf"; die "pg_restore failed"; }
    rm -f "$envf"
  fi
  say "restored the control plane from $RESTORE"
fi

printf 'QOD_VERSION=%s\n' "$TARGET" > "$VERSION_ENV.tmp" && mv -f "$VERSION_ENV.tmp" "$VERSION_ENV"
chmod 644 "$VERSION_ENV"
if ! compose up -d --wait --remove-orphans >/dev/null; then
  say "QoD $TARGET is not healthy: journalctl -t qod-manager. Back with: switch.sh $CURRENT"
  exit 1
fi
say "QoD $TARGET runs on this host (was $CURRENT)"
