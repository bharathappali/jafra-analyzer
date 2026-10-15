# v2 ingest (identity, detail tables, compaction)

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

`jvm_instance`, `active_setting`, `gauge`, `counter_delta`, `gc`, `gc_phase`, `sample`, `duration_event`, `stack`, `frame`, and `marker`. Every row includes `namespace`, `pod`, `container`, `jvm_instance_id`, and `t` (epoch nanoseconds).

`sample.kind` is `cpu`, `wall`, `alloc`, `lock`, `native_malloc`, `native_free`, `native_lock`, or `cputime`. `source` is `async_profiler` for `profiler.*` events, and for `jdk.ExecutionSample` / `jdk.NativeMethodSample` when the chunk contains a profiler event or an active setting of `ctimer` / `async`. Other samples stay `jfr`. Lock and native-lock events are written to both `sample` and `duration_event`.

`stack_id` uses the same 16-hex hash as the v1 stack catalog (top 64 frames: method, line, type) but the frames are stored in Parquet, so the id survives a process restart. `frame_id` hashes class, method, descriptor, line, and type. `category` is `app`, `jdk`, `framework`, `vm`, `gc`, `jit`, `native`, or `profiler`. Framework prefixes are `io.quarkus`, `org.springframework`, `io.netty`, `io.vertx`, `org.hibernate`, and `com.zaxxer`. `thread_group` replaces trailing digits with `*`. The thread key is `os_thread_id`, falling back to the Java thread id.

Markers are written at ingest: `gc_pause` at 20 ms (warn at 100 ms, critical at 500 ms), `full_gc`, evacuation and promotion failure, ZGC allocation stall, safepoint (10 ms total or 5 ms time-to-safepoint, warn at 50 ms), `vm_op` at 50 ms, `cpu_throttle` at ratio 0.2 (critical at 0.5), `deopt_burst` at 50 deoptimizations in 10 seconds, code cache full, data loss, `oom`, `exception_burst` at 10k throwables per second, and `jvm_restart`.

A file older than `jafra.compact.min-age` (default 1 hour, by modification time) is merged into `hour-yyyyMMddHH.parquet` in the same partition. The sweep runs every `jafra.compact.interval` (default 10 minutes). `stack` and `frame` hour files drop exact duplicate rows.

Decode runs on a pool of `jafra.decode.threads` (default 4). Catalog writes are serialized on the DuckDB file.

## Rollups

After each chunk, the minutes it touches are recomputed into `rollup_1s`, `rollup_10s`, and `rollup_1m`. One file per bucket is named `bucket-<epoch-ns>.parquet` and replaced when a later chunk touches that bucket. Columns are `metric`, `bucket_t`, `n`, `sum`, `min`, `max`, `p50`, `p90`, `p99`, and `last`. Percentiles are DuckDB `quantile_disc` on the raw rows in that bucket. The 1 minute duration percentiles are not merged from the 1 second files.

`metrics/MetricRegistry` records each metric's id, unit, source events, kind (`gauge`, `counter`, `duration`, `sample-share`), native period, and `min_jdk`. `rss` requires JDK 21.

`top_1m` keeps the top 50 keys per minute for `cpu`, `wall`, `alloc`, `lock`, `io`, and `exceptions`, grouped by `method`, `class`, `monitor`, or `target`. It is written at ingest. Files named `bucket-` and `top-` are left in place by hourly compaction so a bucket can be replaced.
