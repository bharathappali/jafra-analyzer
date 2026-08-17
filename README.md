# Jafra Analyzer

`jafra-analyzer` version `0.1.0` is a Quarkus gRPC receiver. It validates
chunk streams, persists each accepted chunk on the 5 GiB PVC at
`/var/lib/jafra/analyzer`, stitches contiguous chunks into a per-recording
JFR file, and serves automated analysis summaries over HTTP.

Durable identity is the presence of `chunks/<chunkId>.meta` after a checksum
match. The agent can retry or the analyzer can restart; the same chunk ID
returns `DUPLICATE` and is not written again. `ACCEPTED` is returned only
after that metadata file is durable.

## PVC layout

```text
/var/lib/jafra/analyzer/
  tmp/<chunkId>.part                 # in-flight frames; discarded on abort/recover
  chunks/<chunkId>.jfr               # committed payload
  chunks/<chunkId>.meta              # durable identity
  recordings/<cluster>/<podUID>/<container>/<file>/
    stitched.jfr                     # contiguous chunks from offset 0
    manifest.json                    # next expected offset
  identities/<podUID>.json           # namespace + pod name for HTTP queries
```

Incomplete `.part` files and payload files without `.meta` are deleted on
startup. Out-of-order chunks stay on disk until the hole at offset 0 is
filled, then stitching appends the contiguous prefix.

## Build

```bash
mvn -f jafra-analyzer/pom.xml test
docker build -f jafra-analyzer/Dockerfile -t quay.io/bharathappali/jafra-analyzer:0.1.0 .
```

Build the container from the repository root. Protobuf code is generated from
the single canonical file `contracts/jafra.proto`. Protocol version `1` is
required.

## Deploy

```bash
kind load docker-image quay.io/bharathappali/jafra-analyzer:0.1.0 --name jafra
kubectl apply -f deploy/analyzer/deployment.yaml
kubectl rollout status deployment/jafra-analyzer -n jafra-system
kubectl logs -n jafra-system deployment/jafra-analyzer
kubectl exec -n jafra-system deploy/jafra-analyzer -- ls -la /var/lib/jafra/analyzer
```

Restart the analyzer and confirm previously accepted IDs stay `DUPLICATE`
while `stitched.jfr` remains.

Ports: `9090` gRPC, `8080` HTTP (`GET /health`, `GET /api/v1/status`,
`GET /q/health`, `GET /q/metrics`). Status includes `durableChunks` and
`stitchedBytes`.

## Summary APIs

Recordings are indexed by **namespace**, **pod name**, and **container**.
The HTTP API never uses JVM IDs. Pod UIDs stay on disk for uniqueness.

Query any combination of filters:

```bash
kubectl -n jafra-system port-forward svc/jafra-analyzer 8080:8080
curl 'http://127.0.0.1:8080/api/v1/recordings'
curl 'http://127.0.0.1:8080/api/v1/recordings?namespace=default'
curl 'http://127.0.0.1:8080/api/v1/recordings?namespace=default&pod=auth-cache-abc&container=auth-cache'
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
(up to 7 days). `from` / `to` / `before` / `after` are ISO-8601 timestamps.
`from` alone reads through now; `to` alone reads from the earliest stored
file. Do not combine `last`, `from`/`to`, `before`, and `after`. A valid
window with no overlapping files returns `404`. The report includes
`recordings` (the merged files), `start`/`end` (actual coverage), and
`from`/`to` (the requested window).

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

Many JDK Mission Control rules expect event types that async-profiler does
not emit by itself (GC pauses, compilation, socket IO, code cache). Those
findings come back as not applicable (`score: -1`) unless the recording was
started with `jfrsync=default`.

The init container writes `.jafra-identity.json` next to the recordings so
the agent can send the pod name on ingest. Recordings ingested before that
file exists are omitted from name queries until a later named chunk for
the same pod UID arrives.

## Intentional limitations

- Stitching concatenates finalized JFR chunks. On-demand reports and event
  summaries can merge overlapping rotations for a time window. `/report` uses
  JMC rules; `/summary` returns raw per-event aggregates. It does not upload
  to S3.
- The PVC is ReadWriteOnce; a single replica owns the store. Analyzer memory
is sized for `jfrsync=default` recordings (`-Xmx1024m`, 2Gi limit).
- Duplicate detection is local to this volume. Replacing the PVC looks like
  a first-time ingest to the analyzer.
- `quarkus.log.console.json` is ignored unless `quarkus-logging-json` is
  added. Chunk and stitch metadata are already JSON strings in log messages.
- The gRPC server currently uses Quarkus legacy separate-server mode on port
  `9090`.
