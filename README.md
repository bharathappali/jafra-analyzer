# Jafra Analyzer

`jafra-analyzer` version `0.0.2` is a Quarkus gRPC receiver. It validates
chunk streams, persists each accepted chunk on the 5 GiB PVC at
`/var/lib/jafra/analyzer`, and serves automated analysis summaries over HTTP.
`/report` and `/summary` pass contiguous chunk files to JMC in stitch order.
There is no second copy of those bytes.

Durable identity is the chunk id in the file name, the same id the agent already sends. The agent can retry or the analyzer can restart; the same chunk id returns `DUPLICATE` and is not written again. `ACCEPTED` is returned only after the meta file is durable.

## PVC layout

```text
/var/lib/jafra/analyzer/
  tmp/<chunkId>.part                              # in-flight frames; discarded on abort/recover
  data/<4-hour>/<search>-<chunkId>                # committed payload (durable)
  meta/<4-hour>/<search>-<chunkId>                # same name as the payload
  identities/<podUID>.json                        # namespace + pod name for HTTP queries
```

`<search>` is 60 characters: start and end time in seconds (8 hex digits each), then namespace, pod name, and container as the first three and last three letters written in hex, then the first 8 characters of the pod uid. `<chunkId>` is the existing duplicate key. The 4-hour folder is `startSeconds / 14400`, also 8 hex digits. The same name is used in `data/` and `meta/`.

A query opens the 4-hour folders, lists names, and keeps files whose name matches the requested time, namespace, pod, and container. It opens meta only for those files. Startup does not load every meta into memory.

Files left in the old `chunks/<chunkId>.jfr` and `chunks/<chunkId>.meta` layout are moved into `data/` and `meta/` on startup. A payload with no meta is deleted. The move reads the JFR header once when the old meta has no start time.

Out-of-order chunks stay on disk until the hole at offset 0 is filled; only the contiguous prefix is passed to JMC. Startup deletes a leftover `stitch-cache/` directory from older builds. It never deletes `data/` or `meta/`.

## Build

```bash
mvn -f jafra-analyzer/pom.xml test
docker build -f jafra-analyzer/Dockerfile -t quay.io/bharathappali/jafra-analyzer:0.0.2 .
```

Build the container from the repository root. Protobuf code is generated from
the single canonical file `contracts/jafra.proto`. Protocol version `1` is
required.

## Deploy

```bash
kind load docker-image quay.io/bharathappali/jafra-analyzer:0.0.2 --name jafra
kubectl apply -f deploy/analyzer/deployment.yaml
kubectl rollout status deployment/jafra-analyzer -n jafra-system
kubectl logs -n jafra-system deployment/jafra-analyzer
kubectl exec -n jafra-system deploy/jafra-analyzer -- ls -la /var/lib/jafra/analyzer
```

Restart the analyzer and confirm previously accepted IDs stay `DUPLICATE`
(payloads remain under `data/`).

Ports: `9090` gRPC, `8080` HTTP (`GET /health`, `GET /api/v1/status`,
`GET /q/health`, `GET /q/metrics`). Status includes `durableChunks` and
`stitchedBytes` (always `0`; kept so older clients still parse the JSON).

## Summary APIs

Recordings are indexed by **namespace**, **pod name**, and **container**.
The HTTP API never uses JVM IDs. Pod UIDs stay on disk for uniqueness.

### Flat workload URLs (canonical)

Use query parameters for workload identity on the flat resources:

```bash
kubectl -n jafra-system port-forward svc/jafra-analyzer 8080:8080
curl 'http://127.0.0.1:8080/api/v1/recordings'
curl 'http://127.0.0.1:8080/api/v1/recordings?namespace=default&pod=auth-cache-abc&container=auth-cache'
curl 'http://127.0.0.1:8080/api/v1/workload?namespace=default&pod=auth-cache-abc&container=auth-cache'
curl 'http://127.0.0.1:8080/api/v1/report?namespace=default&pod=auth-cache-abc&container=auth-cache&last=5m'
curl 'http://127.0.0.1:8080/api/v1/summary?namespace=default&pod=auth-cache-abc&container=auth-cache&filter=heap'
curl 'http://127.0.0.1:8080/api/v1/summary?namespace=default&pod=auth-cache-abc&container=auth-cache&recording=profile-3.jfr'
```

Nested paths under `/api/v1/namespaces/...` remain for older clients. They
respond with **HTTP 399** and a `Location` header pointing at the matching
flat URL (same query string). Treat that as a permanent redirect to the flat
API.

Query any combination of filters on `/api/v1/recordings`:

```bash
curl 'http://127.0.0.1:8080/api/v1/recordings?namespace=default'
```

Each file in those listings includes ISO-8601 `start` and `end` from the
JFR chunk headers (nanoseconds since epoch, rendered as UTC).

Then fetch the Cryostat-style automated analysis map (rule id →
`score` / `name` / `topic` / `description`). Scores match JMC:
`-1` not applicable, `-3` ignored/failed, `0` OK, `25` info, `75` warning,
or a finer 0–100 score when a rule supplies one.

```bash
curl 'http://127.0.0.1:8080/api/v1/namespaces/default/pods/auth-cache-abc/containers/auth-cache'
curl 'http://127.0.0.1:8080/api/v1/namespaces/default/pods/auth-cache-abc/containers/auth-cache/report'
curl 'http://127.0.0.1:8080/api/v1/namespaces/default/pods/auth-cache-abc/containers/auth-cache/recordings/profile-3.jfr/report?filter=heap'
```

`GET .../report` with no time query analyzes the latest *closed* loop file
(the newest file that already has a successor). The live file the JVM is
still writing is skipped because JMC rules need a complete chunk set. Pass
`?recording=profile-N.jfr` or use the per-file URL for one rotation.
`?filter=` keeps rules whose id, name, or topic contains the text.

Time windows merge every rotation that overlaps the requested range,
including the live file when it overlaps:

```bash
curl 'http://127.0.0.1:8080/api/v1/namespaces/default/pods/auth-cache-abc/containers/auth-cache/report?last=5m'
curl 'http://127.0.0.1:8080/api/v1/namespaces/default/pods/auth-cache-abc/containers/auth-cache/report?last=10mins'
curl 'http://127.0.0.1:8080/api/v1/namespaces/default/pods/auth-cache-abc/containers/auth-cache/report?last=1h'
curl 'http://127.0.0.1:8080/api/v1/namespaces/default/pods/auth-cache-abc/containers/auth-cache/report?from=2026-08-17T09:00:00Z&to=2026-08-17T09:10:00Z'
curl 'http://127.0.0.1:8080/api/v1/namespaces/default/pods/auth-cache-abc/containers/auth-cache/report?before=2026-08-17T09:10:00Z'
curl 'http://127.0.0.1:8080/api/v1/namespaces/default/pods/auth-cache-abc/containers/auth-cache/report?after=2026-08-17T09:00:00Z'
```

`last` accepts `5m`, `5mins`, `5 minutes`, `1h`, `1 hour`, `90s`, and `1d`
(up to 7 days) and is a wall-clock window ending at request time. Analysis
reads the overlapping recordings' chunk files in the order they used to be
stitched: recording order, then offset order inside each recording. `/report`
and `/summary` then filter events to the requested `from`/`to`. Absolute
`from` / `to` / `before` / `after` are ISO-8601 timestamps. `from` alone reads
through now; `to` alone reads from the earliest stored file. Do not combine
`last`, `from`/`to`, `before`, and `after`. A valid window with no overlapping
files returns `404`. The report includes `recordings` (overlapping files),
`start`/`end` (their coverage), and `from`/`to` (the requested window).

`GET .../summary` is the raw event companion to `/report`. It does not run JMC
rules. It groups event types that appear in the recording and returns the
event count plus values taken from those events: distinct text fields (CPU
type, JVM name, OS version, …) and min/max/avg of numeric measurements.
Addresses, identifiers, JMC type objects (`Type(jdk.CPULoad)`), timestamps-as-epoch, empty event types, and unbounded
sentinels (`Long.MAX_VALUE`) are omitted. Sum is included only for memory
fields. Topics (`heap`, `cpu`, `lock`, …) are a grouping of those event types
and copy text fields into `stats`. The same time window query parameters apply.

```bash
curl 'http://127.0.0.1:8080/api/v1/namespaces/default/pods/auth-cache-abc/containers/auth-cache/summary'
curl 'http://127.0.0.1:8080/api/v1/namespaces/default/pods/auth-cache-abc/containers/auth-cache/summary?last=5m'
curl 'http://127.0.0.1:8080/api/v1/namespaces/default/pods/auth-cache-abc/containers/auth-cache/summary?filter=heap'
curl 'http://127.0.0.1:8080/api/v1/namespaces/default/pods/auth-cache-abc/containers/auth-cache/summary?from=2026-08-17T09:00:00Z&to=2026-08-17T09:10:00Z'
curl 'http://127.0.0.1:8080/api/v1/namespaces/default/pods/auth-cache-abc/containers/auth-cache/recordings/profile-3.jfr/summary'
```

### Collect APIs (targeted JFR reads)

These endpoints share the same workload query parameters and time-window rules
as `/summary` and `/report`:

```bash
curl 'http://127.0.0.1:8080/api/v1/event-types?namespace=default&pod=auth-cache-abc&container=auth-cache&recording=profile-3.jfr'
curl 'http://127.0.0.1:8080/api/v1/events?namespace=default&pod=auth-cache-abc&container=auth-cache&types=JVMInformation&view=summary'
curl 'http://127.0.0.1:8080/api/v1/events?namespace=default&pod=auth-cache-abc&container=auth-cache&types=ExecutionSample&view=records&limit=20'
curl 'http://127.0.0.1:8080/api/v1/jvm?namespace=default&pod=auth-cache-abc&container=auth-cache&recording=profile-3.jfr'
curl 'http://127.0.0.1:8080/api/v1/jvm/flags?namespace=default&pod=auth-cache-abc&container=auth-cache&names=MaxHeapSize,UseG1GC'
```

`GET /api/v1/events` requires `types=` (comma-separated JFR type ids or
suffixes such as `JVMInformation`). `view` is `summary` (default), `records`,
or `series` (one type plus `field=`). Optional `minDuration` filters duration
fields on records; `limit` caps rows (records default 50, max 500; series
default 500, max 5000).

### Aggregation APIs (algorithm helpers)

Pre-computed views aligned with `analysis_algo.md` playbooks:

```bash
curl 'http://127.0.0.1:8080/api/v1/aggregations/gc-pauses?namespace=default&pod=auth-cache-abc&container=auth-cache&recording=profile-3.jfr'
curl 'http://127.0.0.1:8080/api/v1/aggregations/trends?namespace=default&pod=auth-cache-abc&container=auth-cache&last=5m'
curl 'http://127.0.0.1:8080/api/v1/aggregations/allocations?namespace=default&pod=auth-cache-abc&container=auth-cache&limit=20'
curl 'http://127.0.0.1:8080/api/v1/aggregations/samples?namespace=default&pod=auth-cache-abc&container=auth-cache&flavor=cpu'
curl 'http://127.0.0.1:8080/api/v1/aggregations/contention?namespace=default&pod=auth-cache-abc&container=auth-cache&minDuration=20ms'
curl 'http://127.0.0.1:8080/api/v1/aggregations/exceptions?namespace=default&pod=auth-cache-abc&container=auth-cache'
```

`kind` is one of `gc-pauses`, `trends`, `allocations`, `samples`, `contention`,
or `exceptions`. `limit` defaults to 20 (max 100) for ranked `top` entries.
`flavor` on `samples` is `cpu`, `wall`, or `all` (default). `contention`
defaults `minDuration` to `20ms` when omitted.

### Bundle APIs (one-shot playbooks)

`GET /api/v1/bundles` lists declarative bundle ids. Each `GET /api/v1/bundles/{id}`
returns a single JSON document with shared `context` and named `sections` that
compose collect and aggregation calls for an `analysis_algo.md` playbook:

```bash
curl 'http://127.0.0.1:8080/api/v1/bundles'
curl 'http://127.0.0.1:8080/api/v1/bundles/version-check?namespace=default&pod=auth-cache-abc&container=auth-cache&recording=profile-3.jfr'
curl 'http://127.0.0.1:8080/api/v1/bundles/gc?namespace=default&pod=auth-cache-abc&container=auth-cache&last=5m'
curl 'http://127.0.0.1:8080/api/v1/bundles/memory?namespace=default&pod=auth-cache-abc&container=auth-cache&limit=20'
curl 'http://127.0.0.1:8080/api/v1/bundles/performance?namespace=default&pod=auth-cache-abc&container=auth-cache&flavor=cpu'
```

Bundle ids: `version-check`, `memory`, `gc`, `threads`, `performance`,
`jvm-config`, `exceptions`, `diagnostics`, and `proactive`. Optional query
params: `limit`, `minDuration` (threads contention), `flavor` (performance
samples), and `names` (jvm-config flag filter). Workload and time-window
params match the collect and aggregation endpoints.

Many JDK Mission Control rules expect event types that async-profiler does
not emit by itself (GC pauses, compilation, socket IO, code cache). Those
findings come back as not applicable (`score: -1`) unless the recording was
started with `jfrsync=default`.

The init container writes `.jafra-identity.json` next to the recordings so
the agent can send the pod name on ingest. Recordings ingested before that
file exists are omitted from name queries until a later named chunk for
the same pod UID arrives.

## Intentional limitations

- Analysis reads finalized JFR chunks through JMC in stitch order. It does not
  write a concatenated file. On-demand reports and event summaries can merge
  overlapping rotations for a time window. `/report` uses JMC rules; `/summary`
  returns raw per-event aggregates. It does not upload to S3.
- The PVC is ReadWriteOnce; a single replica owns the store. Analyzer memory
is sized for `jfrsync=default` recordings (`-Xmx1024m`, 2Gi limit).
- Duplicate detection is local to this volume. Replacing the PVC looks like
  a first-time ingest to the analyzer.
- `quarkus.log.console.json` is ignored unless `quarkus-logging-json` is
  added. Chunk metadata is already JSON strings in log messages.
- The gRPC server currently uses Quarkus legacy separate-server mode on port
  `9090`.
