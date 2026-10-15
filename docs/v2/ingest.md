# v2 ingest (identity and first tables)

On each committed chunk, and again at startup for any chunk not already marked ok, the analyzer decodes the payload once with `jdk.jfr.consumer.RecordingFile` and writes zstd Parquet under `jafra.storage.root`:

```text
v2/<table>/namespace=<ns>/pod=<pod>/container=<c>/jvm_instance_id=<id>/date=<yyyy-mm-dd>/<chunk_id>.parquet
```

`v2/catalog.duckdb` holds the `chunk_ingest` manifest. A second delivery of the same chunk id is a no-op when status is `ok`.

## JVM instance

`jvm_instance_id` is the first 16 hex characters of SHA-256 over

`namespace|pod_name|container_name|pod_uid|jvmStartTimeMillis|pid`

from `jdk.JVMInformation`. If that event is missing, the chunk inherits the id of the latest ok chunk with the same `recording_id` whose end is at or before this chunk. Otherwise the id is provisional (`provisional|<recording_id>`). A later chunk that does carry `JVMInformation` rewrites the manifest id. The earlier Parquet files stay in the provisional partition until a later compaction.

`instance_seq` counts JVM starts for the same namespace, pod, and container. A sequence above 1 writes a `jvm_restart` marker into both the new instance partition and the previous one. `restart_reason_hint` is `oom_suspected` when the previous instance saw `OutOfMemoryError` or a shutdown reason that says out of memory, otherwise `unknown`.

Counter deltas (`counter_delta`) are computed only inside one instance. A cumulative value that drops is skipped.

## Tables in this cut

`jvm_instance`, `active_setting`, `gauge`, `counter_delta`, `gc`, `gc_phase`, and `marker` (restart only). Every row includes `namespace`, `pod`, `container`, `jvm_instance_id`, and `t` (epoch nanoseconds).

Decode runs on a pool of `jafra.decode.threads` (default 4). Catalog writes are serialized on the DuckDB file.
