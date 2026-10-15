# /v2 read API

Every endpoint except `GET /v2/workloads` requires `ns`, `pod`, `container`, and `instance`. `instance=latest` resolves to the newest JVM start and the response echoes that id. A missing `instance` is 400.

The time window is `last` (default `15m`, wall clock ending now, max `7d`) or a closed `from`/`to` pair. `from` and `to` are ISO-8601 or epoch milliseconds. `before` and `after` are rejected; they stay on `/api/v1`. `compare_from` and `compare_to` are a second closed range on `/v2/top` and `/v2/compare`. `step` is `auto`, `1s`, `10s`, `1m`, `5m`, or `1h`. It is not a window.

`auto` picks the smallest step with at most 240 points: 1s up to 4 minutes, 10s up to 40 minutes, 1m up to 4 hours, 5m up to 20 hours, and 1h after that. 5m and 1h are grouped from the 1m rollup. Percentile series at those steps use the max 1m percentile in the bucket. A requested step finer than the metric's native period, or finer than the 240-point cap, returns `status: coarser_than_requested` and the step that was used. Missing buckets stay null. They are not interpolated.

`format=compact` (the default) rounds to 3 significant figures, sends stack ids without frame text, and omits null object fields. Nulls stay in series arrays. Bytes are integers. `format=full` keeps the raw doubles.

A series or overview block is `unavailable` when the JVM cannot produce that signal. The body then includes `reason` (`jdk_version`, `gc_type`, `jvm_flag`, `not_in_container`, `disabled_by_profiler`, `preset_threshold`, `not_exposed_by_cgroup`), `hint`, and `min_jdk` when the JDK is too old. A supported signal with no samples in the window is `no_data_in_window`. A supported signal that counted zero stays `ok` with zeros. `GET /v2/instance` returns the JVM facts, every signal verdict, and `ERGONOMIC_SERIAL_GC` when Serial was selected ergonomically on fewer than 2 CPUs or under 1792 MiB. `GET /v2/heap` returns one picture per collection. G1 uses `G1HeapSummary`, Parallel uses `PSHeapSummary`, and ZGC uses the young and old collection events. Serial uses `GCHeapSummary` plus `YoungGenerationConfiguration` and sets `estimated: true` on the young capacity. Region frames are counts by type when G1 region events exist. At most 500 collections are returned. Network stays `no_data_in_window`. `GET /v2/findings` runs the detectors in `signatures.yaml` on the 1 minute rollups, heap pictures, markers, and top keys in the window. An empty window is `no_data_in_window`. A window with data and no detector hit returns `HEALTHY`. Scenario fixtures are optional; the signature test stays disabled until `src/test/resources/jfr-fixtures/scenarios/` exists.

| Endpoint | Reads |
|----------|--------|
| `GET /v2/workloads` | `jvm_instance` |
| `GET /v2/instance` | `jvm_instance`, `signal`, `finding` |
| `GET /v2/heap` | `heap_gc`, `g1_region_frame` |
| `GET /v2/findings` | `rollup_1m`, `heap_gc`, `marker`, `top_1m`, `finding` |
| `GET /v2/series?metrics=` | `rollup_1s`, `rollup_10s`, `rollup_1m` |
| `GET /v2/top?kind=&group=&n=` | `top_1m` |
| `GET /v2/events?type=&cursor=&limit=` | `gc`, `gc_phase`, `duration_event` |
| `GET /v2/markers?kinds=&min_severity=` | `marker` |
| `GET /v2/stacks/{id}?depth=` | `stack`, `frame` |
| `GET /v2/compare?metrics=&compare_from=&compare_to=` | 1m rollups |
| `GET /v2/overview` | 1m rollups |

`/v2/top` returns `insufficient_samples` when the window has fewer than 300 samples. `ci95` is the normal 95% half-width of the share. More than 500 markers are clustered per minute (`count`, `max`) instead of listed. Event cursors are exclusive timestamps. `group=thread_group` and `kind=native` return `no_data_in_window` until those rankings are stored.
