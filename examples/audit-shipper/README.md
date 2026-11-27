# Shipping the QoD audit file to object storage

With `QOD_AUDIT_SINK=file` the manager appends every audit and statement event to a local file,
one redacted JSON object per line. QoD stops there: moving the file to long-term storage is the job
of a log shipper. This folder is a reference setup for [Vector](https://vector.dev) **0.62.0**
that ships the file, line for line, into Amazon S3 or an S3-compatible store, Google Cloud Storage
or Azure Blob Storage. No provider is preferred; pick one sink file.

## Files

| File | Content |
|---|---|
| `common.yaml` | The `file` source on `/var/log/qod/audit.ndjson*`, a `remap` that sets the event time from the line's `ts`, global `data_dir`, `timezone: UTC`, end-to-end acknowledgements. Two unit tests: `vector test common.yaml`. |
| `sink-aws-s3.yaml` | `aws_s3`: Amazon S3, or any S3-compatible store through `AWS_ENDPOINT_URL`. |
| `sink-gcp-cloud-storage.yaml` | `gcp_cloud_storage`. |
| `sink-azure-blob.yaml` | `azure_blob`. |
| `qod-audit-shipper.service` | A hardened systemd unit running Vector with `common.yaml` and the sink file you installed. |

Every sink writes gzip NDJSON objects under `qod-audit/date=YYYY-MM-DD/`, the UTC date of each
line's `ts`, named `<upload epoch>-<uuid>.ndjson.gz` (`.gz` on Azure). Objects are cut every 60
seconds or at 10 MB of events. Each line is shipped exactly as QoD wrote it (the `text` codec writes
the raw line, no JSON re-encoding); `ts` is only read for the date. Each sink has a 1 GiB disk
buffer that blocks when full and a disabled healthcheck (see "Credentials").

## Setup

QoD side (manager environment):

```bash
QOD_AUDIT_SINK=file
QOD_AUDIT_FILE_PATH=/var/log/qod/audit.ndjson
QOD_AUDIT_FILE_GROUP_READABLE=true     # 0750 folder, 0640 files, readable by the manager's group
QOD_AUDIT_FILE_MAX_BYTES=104857600     # see "Sizing"
QOD_AUDIT_FILE_MAX_FILES=10
QOD_AUDIT_FILE_FSYNC_INTERVAL_SEC=1
```

The path appears in `common.yaml` (`include`) and the unit (`ReadOnlyPaths`): change all three
together. QoD sets modes only on folders it creates; a `/var/log/qod` that already exists must be
`0750` and owned by the manager's group. QoD rotates the files itself, by rename; never point
logrotate at them.

Shipper side, with Vector installed from its packages (`/usr/bin/vector`) and the manager running in
group `qod` (else change `SupplementaryGroups=` in the unit):

```bash
useradd --system --no-create-home --shell /usr/sbin/nologin qod-audit-shipper
install -d -m 0750 -g qod-audit-shipper /etc/qod-audit-shipper
install -m 0640 -g qod-audit-shipper common.yaml sink-aws-s3.yaml /etc/qod-audit-shipper/
install -m 0600 /dev/null /etc/qod-audit-shipper/credentials.env    # root:root, then fill it in
install -m 0644 qod-audit-shipper.service /etc/systemd/system/
vector validate --no-environment /etc/qod-audit-shipper/*.yaml
systemctl daemon-reload && systemctl enable --now qod-audit-shipper
```

- Copy exactly one `sink-*.yaml`: the unit loads `/etc/qod-audit-shipper/*.yaml`, and two sink
  files ship every line to both.
- Set the destination in that file: `bucket` (S3, GCS), `account_name` and `container_name`
  (Azure). The key prefix `qod-audit/` is the `key_prefix` / `blob_prefix` setting.
- Credentials go in `credentials.env` only (table below). Vector 0.62 no longer interpolates
  `${VAR}` in config files by default, so nothing in the YAML refers to them except the Azure
  sink, which reads its three variables through an `exec` secret backend.
- The state directory (`/var/lib/qod-audit-shipper`, checkpoints and buffers) must be on a
  filesystem with at least 1 GiB per sink: Vector refuses to start otherwise.

## Durability, end to end

1. **QoD queue.** Events wait in the manager's in-memory queue (`QOD_TELEMETRY_JOURNAL_CAPACITY`,
   8192 lines). A full queue or a write error drops lines, counted in
   `qod_journal_dropped_total{table="file"}`.
2. **File and fsync.** Lines are appended to the file and synced every
   `QOD_AUDIT_FILE_FSYNC_INTERVAL_SEC` (0 = after every line).
3. **Vector file source.** It identifies a file by a checksum of its first line, so a rename
   rotation keeps the file's identity and its read position: lines written just before a rotation
   are read from `audit.ndjson.1`, and an open file that QoD deletes is still read to its end. Its
   checkpoint moves only once a line is acknowledged by the sink's disk buffer.
4. **Disk buffer.** A line is acknowledged once written to the buffer; the buffer is synced to disk
   every 500 ms. A batch leaves it once the object store accepted the upload, or once the sink
   gave up on it: timeouts and 5xx are retried without limit, but an error the sink deems
   permanent drops the batch (logged as "dropping the request", counted in Vector's
   `component_discarded_events_total`). `sink-aws-s3.yaml` sets `retry_strategy: {type: all}`, so
   S3 never drops. Vector 0.62.0 has no such setting for the other two: GCS drops on 400, 403 and
   404; Azure on any 4xx but 429, and on a connection error that outlasts the Azure SDK's own
   retries.

| Crash of | Loses |
|---|---|
| the manager | its queue, at most 8192 lines |
| the host | what QoD wrote since its last fsync, and up to the last 500 ms of the disk buffer |
| Vector | nothing: it restarts from its checkpoint and its buffer; lines between the last checkpoint and the crash are shipped twice |
| the object store, or the network to it | nothing while the buffer has room; then Vector stops reading and QoD's files hold the backlog (Azure: see the connection-error case above) |

Delivery is at least once. A line can appear in two objects after a Vector crash or a retried
upload; deduplicate on the whole line if you need exactly once.

## Sizing and recovery

Once the disk buffer is full, the backlog sits in QoD's files, and at the rotation cap QoD deletes
the oldest rotated file, shipped or not (logged at ERROR, counted in
`qod_audit_file_deleted_total`). Size the cap for the longest shipper or store outage you will
tolerate:

```
QOD_AUDIT_FILE_MAX_BYTES x QOD_AUDIT_FILE_MAX_FILES >= audit bytes per second x outage seconds
```

The defaults (100 MiB x 10) hold about one hour at 1,000 events per second of 300 bytes. Measure
your rate from the rotated files' sizes and timestamps.

Alert on any increase of `qod_audit_file_deleted_total` and of
`qod_journal_dropped_total{table="file"}`. Both mean the file may be missing events. Every event is
also in the control-plane Postgres, `qodstate_audit` for `QOD_AUDIT_RETENTION_DAYS` (90 days) and
`qodstate_stmt_history` for `QOD_STMT_HISTORY_RETENTION_DAYS` (7 days), so recover the window of
the deleted file (its first and last `ts`, or the alert's time range) from there, paging with
`--before`:

```bash
qod audit list --from 2026-11-26T08:00:00Z --to 2026-11-26T10:00:00Z --limit 1000
qod history statements --from 2026-11-26T08:00:00Z --to 2026-11-26T10:00:00Z --limit 1000
```

These are the stored rows in the REST API's JSON, not the file's line format, and they are not
redacted the way the file lines are (SQL literals, error text): store the export with the same
care, or not in the archive bucket.

To watch Vector itself (buffer size, upload errors), add an `internal_metrics` source and a
`prometheus_exporter` sink to `common.yaml`.

## Immutable destination

Retention is the store's job; Vector only creates new objects with unique names.

- **S3 Object Lock.** Enable it when creating the bucket (it turns versioning on) and set a default
  retention, so every object Vector writes is locked without per-object headers:

  ```bash
  aws s3api create-bucket --bucket qod-audit --object-lock-enabled-for-bucket
  aws s3api put-object-lock-configuration --bucket qod-audit --object-lock-configuration \
    '{"ObjectLockEnabled":"Enabled","Rule":{"DefaultRetention":{"Mode":"COMPLIANCE","Days":365}}}'
  ```

  `COMPLIANCE` cannot be shortened or bypassed by anyone; `GOVERNANCE` can be by holders of
  `s3:BypassGovernanceRetention`. A PUT into a lock bucket must carry `Content-MD5` or an
  `x-amz-checksum-*` header. Vector 0.62.0's `aws_s3` sends both: `Content-MD5` and
  `x-amz-checksum-crc32` as a header, with a signed payload and `Content-Length` (no aws-chunked
  trailer). S3-compatible stores that implement Object Lock (OVHcloud Object Storage among them)
  take the same calls with `--endpoint-url`; check that yours accepts the CRC32 header.
- **GCS Bucket Lock.** Set a retention policy, then lock it (irreversible: the policy can no
  longer be removed or shortened):

  ```bash
  gcloud storage buckets update gs://qod-audit --retention-period=365d
  gcloud storage buckets update gs://qod-audit --lock-retention-period
  ```

- **Azure immutable blob storage.** A time-based retention policy on the container, then locked:

  ```bash
  az storage container immutability-policy create --account-name qodaudit -c qod-audit --period 365
  az storage container immutability-policy lock --account-name qodaudit -c qod-audit --if-match <etag>
  ```

## Credentials

Each sink needs a credential that can create objects under the prefix and nothing else: it cannot
read, list, overwrite or delete what it shipped. The healthchecks need more than that, so they are
disabled, and Vector starts whatever the credential. On S3 a bad credential then shows as failed
uploads and a growing buffer; on GCS and Azure the sink drops each batch it refuses (see
"Durability"), so check a new credential by shipping one line before you rely on it.

| Sink | `credentials.env` | Write-only grant | Healthcheck would need |
|---|---|---|---|
| `aws_s3` | `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`; `AWS_ENDPOINT_URL` for an S3-compatible store, e.g. `https://s3.gra.io.cloud.ovh.net` with `AWS_REGION=gra`. No keys on EC2 with an instance role. | `s3:PutObject` on `arn:aws:s3:::qod-audit/qod-audit/*` only | `s3:ListBucket` on the bucket (HeadBucket) |
| `gcp_cloud_storage` | `GOOGLE_APPLICATION_CREDENTIALS`: path to a service account key, mode 0400, owned by `qod-audit-shipper`. Nothing on Compute Engine. | `roles/storage.objectCreator` on the bucket (`storage.objects.create`) | `storage.buckets.get` (HEAD on the bucket) |
| `azure_blob` | `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET` of a service principal | a custom role with the single data action `Microsoft.Storage/storageAccounts/blobServices/containers/blobs/write`, assigned on the container | read on the container (Get Container Properties) |

Azure alternatives, described at the top of `sink-azure-blob.yaml`: a managed identity on an Azure
VM (no secret), or a service SAS on the container limited to create and write (`sp=cw`).

## Other shippers

- **Fluent Bit.** `tail` input on the same glob with `DB` (checkpoints) and
  `storage.type filesystem` (disk buffer); `s3` output with `log_key log` (raw line),
  `compression gzip` and `send_content_md5 true` (required by Object Lock), or the `azure_blob`
  output.
- **OpenTelemetry Collector.** `filelog` receiver on the same glob with `start_at: beginning` and
  `storage: file_storage` (offsets survive restarts); `awss3` exporter with `marshaler: body`
  (raw line) and `sending_queue: {storage: file_storage}` (persistent queue). The contrib
  distribution also has Azure Blob and Google Cloud Storage exporters.
