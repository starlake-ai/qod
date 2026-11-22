# QoD on one host, as containers

This folder installs QoD on one Linux server as containers: the manager and its fleet workers, all
from the one image `starlakeai/quack-on-demand`, on Docker Engine, under systemd. The folder beside
it (`deploy/host/`) is the other way to run one host: the manager jar itself under systemd, nodes as
its child processes.

```
one host: Docker Engine 24+, compose v2, cgroup v2
  qod-manager      network host, the fleet runtime
                   :31338 Flight SQL and :9494 Quack, TLS by QoD
                   127.0.0.1:20900 REST and console, 127.0.0.1:20901 verdict listener
  qod-worker-1..N  network host, one node each, on 127.0.0.1:21901..2190N
  qod-postgres     only with --postgres bundled, 127.0.0.1:5432
  systemd          qod.service, qod-health.timer (and qod-backup.timer with bundled PostgreSQL)
```

Every container runs with host networking (Docker publishes no port and writes no firewall rule),
a read-only root filesystem, every capability dropped and its own memory and CPU limits. Fleet
traffic stays on loopback: workers join from `127.0.0.1`, and only that source is auto-approved.

## Install

As root, from this folder at the release tag you install:

```sh
./install.sh --version 0.15.0 --settings /root/qod-settings.env
```

`--settings` holds the manager's own settings, `KEY=VALUE` per line. For the default, external
PostgreSQL and S3:

```sh
QOD_PG_HOST=pg.internal
QOD_PG_PORT=5432
QOD_PG_USER=qod            # a role with CREATEDB, not a superuser
QOD_PG_PASSWORD=...
QOD_DUCKLAKE_DATA_PATH=s3://my-bucket/qod
QOD_S3_ENDPOINT=...        # with QOD_S3_ACCESS_KEY_ID, QOD_S3_SECRET_ACCESS_KEY, QOD_S3_REGION
```

The script copies these into `/etc/qod/qod.env`, then adds the lines this layout owns (fleet mode,
loopback binds, TLS paths, the join token, a generated admin password), which win over the same
keys in the file.

| Option | Default | Meaning |
|---|---|---|
| `--workers N` | from RAM | Worker containers, one of them spare (maintenance and merges need a free worker) |
| `--worker-size <cpus>cpu,<GiB>g` | the rest of the host | The size of each worker |
| `--postgres bundled` | `external` | A `postgres:16` container on `127.0.0.1:5432` with a daily dump. Preview, single host only: durable state then lives on this host |
| `--data-dir DIR` | none | A filesystem data path, mounted at the same path in every container, instead of object storage. No managed storage, and no second host until it moves to S3 |
| `--spill-root DIR` | `/fastdisk/qod-spill` | One folder per worker under it, mounted at `/duckdb-tmp`. Mount a fast local disk at its parent |
| `--image REPO`, `--no-pull` | `starlakeai/quack-on-demand` | Another image, or one already on the host |
| `--name NAME` | the short host name | Prefix of the worker names (`NAME-w1`, ...) |
| `--skip-smoke` | | Skip the smoke test at the end |

It refuses, naming the rule, a host or a size that breaks one of these: Docker Engine 24+ with
compose v2, cgroup v2, `curl`, `jq`, `openssl` and `ss` installed, the ports free, the spill disk's
parent present, PostgreSQL and the object store reachable, and the sizing below.

Then it writes the files below, starts `qod.service`, creates the host's access token, waits for
every worker to be approved and heartbeating, and runs `smoke-test.sh`. The admin password is
`QOD_ADMIN_PASSWORD` in `/etc/qod/qod.env`, used at the first boot only: change it
(`qod auth change-password`), then remove that line.

**Storage keys and the default data path.** Until starlake-ai/qod#285 is fixed, a fleet node reads
the default S3 data path only with the manager's keys in its worker's environment, and this layout
puts no storage key there: with an S3 data path, create databases with their own `objectStore` keys
or with managed storage, or use `--data-dir`.

## Sizing

| Rule | |
|---|---|
| Manager | 4 CPUs, 4 GiB (2 GiB heap). At 2 CPUs a burst of logins times out |
| Worker | at least 2 CPUs, and at least 1 vCPU per 4 GiB |
| A pool's DuckDB `threads` | one less than its worker's CPUs: `qod-node` runs about 33 threads of its own |
| CPUs | the manager's plus every worker's but the spare's, at most the host's |
| Memory | the manager's plus every worker's, at most the host's minus max(4 GiB, 10%) |
| A pool's `memory_limit` | at most 80% of its worker's, so DuckDB spills before the kernel kills the node |

By default: one worker per 24 GiB left after the manager and the reserve, plus the spare, at
least two; the CPUs left after the manager shared by the workers but the spare, and the memory
left shared by all of them. 8 vCPU / 32 GiB gives 2 x (4 CPU, 12 GiB); 32 vCPU / 128 GiB,
5 x (7 CPU, 22 GiB). A branch holds a worker for its whole TTL, so count one per live branch.

## Files

| Path | Owner, mode | Content |
|---|---|---|
| `/etc/qod/version.env` | root, 644 | `QOD_VERSION=<tag>`, the release every container runs |
| `/etc/qod/qod.env` | root, 600 | The manager's settings, the join token, the first-boot admin password |
| `/etc/qod/worker.env` | root, 600 | What every worker shares: the manager's address, the join token. No PostgreSQL password, no storage key |
| `/etc/qod/postgres.env` | root, 600 | Bundled PostgreSQL only: its superuser password |
| `/etc/qod/host.env` | root, 644 | The sizing and layout `qod-host render` writes the compose file from. No secret |
| `/etc/qod/compose.yaml` | root, 644 | Generated; edit `host.env` or run `qod-host scale`, never this file |
| `/etc/qod/host.token` | root, 600 | The host's access token (an admin's PAT), used by `qod-host` and the smoke test |
| `/etc/qod/tls/` | UID 1000, 700 | `server-cert.pem`, `server-key.pem` (600) for Flight SQL and Quack: a self-signed pair until you replace it, then `systemctl restart qod` |
| `/var/lib/qod/state` | UID 1000 | The manager's state |
| `/var/lib/qod/pgdata` | | Bundled PostgreSQL only |
| `/var/lib/qod/backups` | root, 700 | Dumps (600), the 7 latest of each kind (`QOD_BACKUP_KEEP`) |
| `<spill root>/w<i>` | UID 1000, 700 | Worker i's spill folder, under a root-owned spill root |
| `/opt/qod/host/` | root | These scripts; `/usr/local/sbin/qod-host` links to `qod-host` |

Anyone in the `docker` group reads every container's settings (`docker inspect`), secrets
included: that group is root-equivalent.

## Day two

```sh
qod-host status                                   # containers, workers, pools, pending slots, branches
qod-host scale --workers 4                        # add a worker; reconcile fills a pending slot
qod-host scale --workers 3                        # remove a free worker (drained, then stopped)
qod-host scale --workers 2 --move                 # also one that runs a node: restarted elsewhere first
qod-host scale --worker-size 6cpu,20g             # resize, one worker at a time, a pool keeps serving
qod-host backup [--all] [--encrypt-to age1...]    # dump the control plane
./switch.sh 0.15.1                                # another release, after a dump
qod-host uninstall [--purge]
```

**scale** never removes the last spare, refuses a worker that runs a pool's node unless `--move`
is given, and never moves a maintenance or merge node (retry once it ends). A resize recreates the
workers one at a time, free ones first: each is drained, its node restarted on another worker,
then recreated at the new size and undrained, so a pool with two nodes keeps serving.

**backup** dumps the control-plane database (`pg_dump -Fc`). `--all` dumps every database of the
bundled PostgreSQL, the DuckLake catalogs included (`pg_dumpall`); `qod-backup.timer` runs it
daily. With `--encrypt-to` it writes an `age`-encrypted copy beside it: only that copy should
leave the host. A dump holds every secret PostgreSQL holds.

**switch.sh VERSION** pulls the release, dumps the control plane, then recreates every container:
statements drain for up to 60 s first, so it is a short outage. It refuses an upgrade from a
release older than the target's `min_from_version`, and a rollback across a release that changed
the schema one way, unless `--restore-backup FILE` restores the dump taken before the upgrade.

**Restarts.** `restart: unless-stopped` brings back an exited container, a JVM that ran out of heap
(the manager runs with `-XX:+ExitOnOutOfMemoryError`) and everything after a reboot. A worker that
hits its memory cap loses its node only, which its join restarts. A hung container turns unhealthy
and `qod-health.timer` restarts it within about two minutes (Docker never does).

## Smoke test

`smoke-test.sh --manager` checks `/ready`, TLS on 31338 and 9494 (and 443 when a reverse proxy
listens there), the host's token, that 20900, 20901, the node ports and 5432 listen on loopback
only, that no container publishes a port, and that no secret of `qod.env` shows in a worker's
processes or settings, on a command line of the host or in the containers' journal.
`smoke-test.sh --queries` creates a throwaway tenant with a one-node pool and three users, then
checks a write and a read, a refusal, a masked column and a time-travel read through MCP `run_sql`,
and deletes the tenant.
