# v2 ingest (identity, detail tables, compaction)

On each committed chunk, and again at startup for any chunk not already marked ok, the analyzer decodes the payload once with `jdk.jfr.consumer.RecordingFile` and writes zstd Parquet under `jafra.storage.root`:

```text
v2/<table>/namespace=<ns>/pod=<pod>/container=<c>/jvm_instance_id=<id>/date=<yyyy-mm-dd>/<chunk_id>.parquet
```

`v2/catalog.duckdb` holds the `chunk_ingest` manifest. A second delivery of the same chunk id is a no-op when status is `ok`.

## JVM instance

`jvm_instance_id` is the first 16 hex characters of SHA-256 over

`namespace|pod_name|container_name|pod_uid|jvmStartTimeMillis|pid`

from `jdk.JVMInformation`. If that event is missing, the chunk inherits an ok chunk of the same `recording_id`. One file is one JVM, so a later chunk still belongs to that instance when it starts inside an earlier chunk's declared window. A recording that already has both a real id and a provisional id prefers the real one. When this file has no earlier chunk, it inherits the newest real instance for the same namespace, pod, and container whose last chunk ended within 15 seconds of this chunk, and otherwise a provisional instance that still abuts this chunk the same way. A new provisional id (`provisional|<recording_id>`) is minted only when none of those exist. A rotated `profile-N.jfr` that continues the recording stays on the JVM that already identified itself. A chunk that starts later than that 15 second slack is a new JVM and does not attach to the previous one.

When a chunk carries `JVMInformation`, every already ingested chunk of that workload whose start is at or after that JVM start (1 second of slack) moves onto the real id. `chunk_ingest` is rewritten, provisional `jvm_instance_state` rows that lose every chunk are deleted, and the Parquet files move with the `jvm_instance_id` column rewritten. Chunks that started earlier stay on the previous JVM, and that JVM's `last_t_ns` is recomputed from the chunks it still has. Rollups for the moved minutes are rebuilt from the raw rows.

`instance_seq` counts JVM starts for the same namespace, pod, and container. The next sequence follows the highest `instance_seq`, then the latest `last_t_ns`. A sequence above 1 writes a `jvm_restart` marker into both the new instance partition and the previous one. `restart_reason_hint` is `oom_suspected` when the previous instance saw `OutOfMemoryError` or a shutdown reason that says out of memory, otherwise `unknown`.

Counter deltas (`counter_delta`) are computed only inside one instance. A cumulative value that drops is skipped.

## Tables in this cut

`jvm_instance`, `active_setting`, `gauge`, `counter_delta`, `gc`, `gc_phase`, `sample`, `duration_event`, `stack`, `frame`, and `marker`. Every row includes `namespace`, `pod`, `container`, `jvm_instance_id`, and `t` (epoch nanoseconds).

`sample.kind` is `cpu`, `wall`, `alloc`, `lock`, `native_malloc`, `native_free`, `native_lock`, or `cputime`. `source` is `async_profiler` for `profiler.*` events, and for `jdk.ExecutionSample` / `jdk.NativeMethodSample` when the chunk contains a profiler event or an active setting of `ctimer` / `async`. Other samples stay `jfr`. Lock and native-lock events are written to both `sample` and `duration_event`.

`stack_id` uses the same 16-hex hash as the v1 stack catalog (top 64 frames: method, line, type) but the frames are stored in Parquet, so the id survives a process restart. `frame_id` hashes class, method, descriptor, line, and type. `category` is `app`, `jdk`, `framework`, `vm`, `gc`, `jit`, `native`, or `profiler`. Framework prefixes are `io.quarkus`, `org.springframework`, `io.netty`, `io.vertx`, `org.hibernate`, and `com.zaxxer`. `thread_group` replaces trailing digits with `*`. The thread key is `os_thread_id`, falling back to the Java thread id.

Markers are written at ingest: `gc_pause` at 20 ms (warn at 100 ms, critical at 500 ms), `full_gc`, evacuation and promotion failure, ZGC allocation stall, safepoint (10 ms total or 5 ms time-to-safepoint, warn at 50 ms), `vm_op` at 50 ms, `cpu_throttle` at ratio 0.2 (critical at 0.5), `deopt_burst` at 50 deoptimizations in 10 seconds, code cache full, data loss, `oom`, `exception_burst` at 10k throwables per second, and `jvm_restart`.

A file older than `jafra.compact.min-age` (default 1 hour, by modification time) is merged into `hour-yyyyMMddHH.parquet` in the same partition. The sweep runs every `jafra.compact.interval` (default 10 minutes). `stack` and `frame` hour files drop exact duplicate rows.

Decode runs on a pool of `jafra.decode.threads` (default 4). Catalog writes are serialized on the DuckDB file.

## Heap pictures

`heap_gc` has one row per `jdk.GarbageCollection`, plus a row for each ZGC young or old collection that is not already that id. Before and after come from `jdk.GCHeapSummary`. G1 adds eden and survivor from `jdk.G1HeapSummary`; old used is heap minus those two, and promoted bytes are the growth in old. Parallel reads eden, survivor, and old from `jdk.PSHeapSummary`. Serial has no generation used-sizes: the row keeps the heap totals, sets `estimated` when `YoungGenerationConfiguration.newRatio` can size the young capacity as `committed / (newRatio + 1)`, and copies `tenuringThreshold` from `jdk.YoungGarbageCollection`. `g1_region_frame` stores a count and used total per region type. Humongous region counts land on the collection with the same `gc_id`.

## Findings

`GET /v2/findings` evaluates `signatures.yaml` on the requested window. The inputs are the 1 minute rollups, `heap_gc`, markers, and `top_1m`. A missing series does not fire its detector. `HEALTHY` is returned only when the window has data and nothing else fired. `ERGONOMIC_SERIAL_GC` is still the ingest `finding` row. Scenario JFR fixtures are not generated; `SignatureTest` runs only when `src/test/resources/jfr-fixtures/scenarios/` exists.

`POST /mcp` calls these reads. `GET /v2/correlate` aligns two 1 minute series and returns the lagged Pearson result. MCP `heap_summary` aggregates `heap_gc` instead of listing every collection.

## Signals and container facts

Each chunk also writes `signal` (one row per id in `signals.yaml`) and, when it applies, a `finding` row `ERGONOMIC_SERIAL_GC`. Container limits come from `jdk.ContainerConfiguration`: `cpu_limit_cores` is quota divided by the slice period (null when the quota is unlimited), `cpu_request_millicores_approx` rounds `cpuShares * 1000 / 1024` to the nearest 10, and cgroup v2 marks that request `approx_v2_weight_mapping`. Shares of 2 are `best_effort`. A negative memory limit is stored as null. `memory_request` is always `not_exposed_by_cgroup`.

`gc_selected_by` is `command_line` when an enabled `Use*GC` flag has origin command line, and `ergonomic` when flag events were seen and none of those flags did. `ERGONOMIC_SERIAL_GC` is recorded when that selection is ergonomic, Serial is in use, and either `effective_cpus` is below 2 or `mem_limit` is below 1792 MiB.

## Retention

`jafra.retention.mode` is `delete-after-48h` or `never`. Age is the time since the instance's last chunk. A live instance (a chunk in the last two minutes) is never purged by the sweep. At 48 hours the sweep deletes the raw chunk and the detail tables (`sample`, `duration_event`, `gauge`, `counter_delta`, `rollup_1s`, `g1_region_frame`, `gc_phase`) and keeps the 10 second and 1 minute rollups, markers, findings, `gc`, `top_1m`, stacks, and `jvm_instance`. The instance becomes `summary_only`. `clear-parquet-cache=true` also drops that summary and leaves a tombstone. `GET /v2/retention/tombstones` is the feed Jafra Z polls. A purged read returns `reason: retention_48h` and the coarser data that is still stored. `DELETE`, `clear-cache`, and `purge-raw` accept `dry_run=true`. There is no bearer token. GET `/v2` responses send an `ETag` from the catalog watermark and the retention file, and JSON is gzip-compressed.

`jvm_instance.preset` is chosen from `jdk.ActiveSetting`: the three shipped files in `src/main/resources/jfc/` match on `jdk.ExecutionSample` period, `jdk.SocketRead` threshold, and `jdk.ContainerCPUThrottling` period. A 20 ms sample with a 20 ms socket read is `default`. Other async-profiler settings are `profile`. Anything else is `unknown`.

## Rollups

After each chunk, the minutes it touches are recomputed into `rollup_1s`, `rollup_10s`, and `rollup_1m`. One file per bucket is named `bucket-<epoch-ns>.parquet` and replaced when a later chunk touches that bucket. Columns are `metric`, `bucket_t`, `n`, `sum`, `min`, `max`, `p50`, `p90`, `p99`, and `last`. Percentiles are DuckDB `quantile_disc` on the raw rows in that bucket. The 1 minute duration percentiles are not merged from the 1 second files.

`metrics/MetricRegistry` records each metric's id, unit, source events, kind (`gauge`, `counter`, `duration`, `sample-share`), native period, and `min_jdk`. `rss` requires JDK 21.

`top_1m` keeps the top 50 keys per minute for `cpu`, `wall`, `alloc`, `lock`, `io`, and `exceptions`, grouped by `method`, `class`, `monitor`, or `target`. It is written at ingest. Files named `bucket-` and `top-` are left in place by hourly compaction so a bucket can be replaced.
