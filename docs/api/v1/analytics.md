# Analytics APIs

[← Index](README.md)

LLM-oriented documents: fixed **14 subsystems**, pre-computed rollups, bucketed **series** (timeseries only), **evidence** top-N, **dataQuality**, and **correlations**.

`schemaVersion` is always **1**.

## Endpoints

| Endpoint | Mode | Series buckets |
|----------|------|----------------|
| `GET /api/v1/analytics/timeseries` | `timeseries` | Yes (60–120 adaptive buckets) |
| `GET /api/v1/analytics/daily_stats` | `daily_stats` | No (`series` omitted) |

### `timeseries`

**Request (profiler 6 s window, subsystem filter)**

```http
GET /api/v1/analytics/timeseries?namespace=default&pod=auth-cache-84cd9fbc49-fttcq&container=auth-cache&from=2026-09-12T22:50:54Z&to=2026-09-12T22:51:00Z&subsystem=cpu,wall_time,allocations&top=5
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `top` | 15 (max 30) | Evidence list sizes |
| `bucketSeconds` | adaptive | Override bucket width (1–3600) |
| `subsystem` | all 14 | Comma filter |

**Full response:** [samples/analytics-timeseries-profiler-window.json](samples/analytics-timeseries-profiler-window.json) (~100 KB)

**Documentation excerpt:** [samples/analytics-timeseries-profiler-window.excerpt.json](samples/analytics-timeseries-profiler-window.excerpt.json)

**Request (60 s JDK window)**

```http
GET /api/v1/analytics/timeseries?namespace=default&pod=auth-cache-84cd9fbc49-fttcq&container=auth-cache&from=2026-09-12T22:50:54Z&to=2026-09-12T22:51:54Z&subsystem=container,heap,gc,cpu&top=5
```

**Excerpt:** [samples/analytics-timeseries-jdk-window.excerpt.json](samples/analytics-timeseries-jdk-window.excerpt.json)

### `daily_stats`

Default **`last=24h`** ending at request time. For golden fixtures (September 2026 data), pass **`from` and `to`** so chunks overlap.

**Request**

```http
GET /api/v1/analytics/daily_stats?namespace=default&pod=auth-cache-84cd9fbc49-fttcq&container=auth-cache&from=2026-09-12T00:00:00Z&to=2026-09-14T00:00:00Z&top=5
```

**Full:** [samples/analytics-daily-stats.json](samples/analytics-daily-stats.json)  
**Excerpt:** [samples/analytics-daily-stats.excerpt.json](samples/analytics-daily-stats.excerpt.json)

## Envelope

```json
{
  "schemaVersion": 1,
  "mode": "timeseries",
  "context": { "from": "...", "to": "...", "chunks": 6, "bytes": 349990 },
  "dataQuality": {
    "windowDurationSeconds": 6,
    "coveragePercent": 100,
    "profilerPresent": true,
    "jdkPeriodicPresent": true,
    "eventTypeCount": 42
  },
  "subsystems": { },
  "correlations": { }
}
```

### `dataQuality.gaps`

Present when requested window extends beyond stored chunk coverage:

```json
{ "from": "2026-09-12T00:00:00Z", "to": "2026-09-12T12:43:20Z", "reason": "no_chunks_before_data" }
```

## Subsystem shape

Each key under `subsystems`:

| Field | timeseries | daily_stats |
|-------|------------|-------------|
| `availability.status` | `present` \| `partial` \| `absent` | same |
| `availability.eventTypesExpected` | list | same |
| `availability.eventTypesSeen` | list | same |
| `rollup` | window aggregates | same |
| `series` | `MetricSeries[]` | omitted |
| `evidence` | top-N maps/lists | same |
| `notes` | LLM hints (strings) | same |

### `MetricSeries`

```json
{
  "id": "jvmCpuPercent",
  "label": "JVM CPU",
  "unit": "percent",
  "description": "High value suggests the JVM process is using CPU time.",
  "points": [
    { "at": "2026-09-12T22:50:54Z", "value": 1.43, "mean": 0.9, "count": 1 }
  ]
}
```

Duration buckets may use `count`, `max` on points (see `pauseSumMsPerBucket`).

## Subsystem reference (all 14)

Golden fixture behavior noted for the windows above.

### `context`

**Expected types:** `JVMInformation`, `GCConfiguration`, `ContainerConfiguration`, `ActiveRecording`

**Rollup:** `jvm`, `gcConfiguration`, `heapConfiguration`, `containerConfiguration`, `os`, `eventTypesProfiler`, `eventTypesJdk`

Usually **present** on any non-empty window. No `series`.

### `cpu`

**Expected:** `ExecutionSample`, `CPULoad`, `ThreadCPULoad`, `profiler.ExecutionSample`

**Rollup (60 s window example):** `cpuSampleCount`, `jvmCpu` {mean,min,max,p50,p95}, `machineTotal` stats

**Series:** `jvmCpuPercent`, `machineTotalPercent`, `cpuSampleRatePerSec`

**Evidence:** `topStacks` with `sampleCount`, `percentOfCpuSamples` — [aggregation-samples-cpu.json](samples/aggregation-samples-cpu.json) shows similar stacks

**Fixture:** **partial** on 6 s profiler window (no `profiler.ExecutionSample`); **present** on 60 s JDK window

### `wall_time`

**Expected:** `profiler.WallClockSample`

**Rollup:** `wallSampleCount`, `stateBreakdownPercent` (e.g. `STATE_SLEEPING` 40%)

**Series:** `wallSamplesPerSec`, `percentSleeping`

**Evidence:** `top` stacks, `topThreads`

**Fixture:** **present** on profiler window; **absent** on pure 60 s JDK-only sub-window if no wall events in range

### `threads`

**Expected:** `JavaThreadStatistics`, `ThreadStart`, `ThreadAllocationStatistics`

**Rollup:** `liveThreads` stats, `threadStartCount`, `threadEndCount`, `contextSwitchRate`

**Series:** `liveThreadCount` when periodic stats exist

**Fixture:** **present** on 60 s JDK chunks

### `heap`

**Expected:** `GCHeapSummary`, `MetaspaceSummary`, `ResidentSetSize`

**Rollup example (60 s):**

```json
{
  "heapUsedSlopePerHour": -6991880.5,
  "heapVsRssGapMeanBytes": 390298259.2,
  "rss": { "mean": 581014528, "max": 584552448 }
}
```

**Series:** `heapUsedBytes`, `rssBytes`

### `allocations`

**Rollup example (profiler window):**

```json
{
  "allocationEventCount": 41,
  "allocationBytesTotal": 819,
  "allocationBytesPerSec": 136.5,
  "outsideTlabRatio": 0.0
}
```

**Evidence:** `topClasses`, `topStacks` — aligns with [aggregation-allocations.json](samples/aggregation-allocations.json)

No `series` in v1 (rates are in rollup).

### `gc`

**Rollup (60 s):** same pause stats as [aggregation-gc-pauses.json](samples/aggregation-gc-pauses.json) plus `youngGcPerHour`, `pauseTimePercentOfWindow`, `p50Ms`/`p95Ms`

**Series:** `pauseSumMsPerBucket`, `youngGcCountPerBucket`

**Evidence:** `longestPauses` (top 5)

**Markers:** GC pauses ≥ 20 ms appear in `correlations.markers` on timeseries

### `locks`

**Rollup:** `contentionEventCount`, `totalBlockedMs`, `minDurationMs` (20)

**Series:** `contentionEventsPerBucket`, `blockedMsPerBucket`

**Evidence:** top monitors / native lock stacks

**Fixture:** sparse; `JavaMonitorWait` on 60 s chunks; `profiler.NativeLock` on profiler chunks

### `jit`

**Rollup:** `compilationCount`, `deoptimizationCount`, `compilerNativeMallocBytes`

**Fixture:** `Deoptimization` count 1 in full corpus; C2 `Malloc` stacks in heavy profiler chunk

### `io`

**Rollup:** `ioOperationCount`, `ioDurationSumMs`, optional `containerIoBytesDelta`

**Fixture:** one `jdk.FileRead` in full window — often low counts

### `container`

**Rollup example (60 s):**

```json
{
  "cpuThrottledTimeDeltaNs": 2355069000,
  "memoryUsage": { "mean": 563015680, "max": 567242752 }
}
```

**Series:** `memoryUsageBytes`

**Fixture:** **present** only when 60 s `jfrsync=default` chunks overlap the window

### `native_memory`

**Rollup:** `mallocCount`, `freeCount`, `mallocBytesTotal`, `mallocBytesPerSec`, `freeEventsPerSec`

**Evidence:** top malloc stacks

**Fixture:** dominant `profiler.Free` (~45k) in full corpus

### `exceptions`

**Rollup:** `throwCount`

**Evidence:** `topClasses`, `topStacks`

**Fixture:** mostly `ExceptionStatistics` periodic events; few throws

### `vm_internal`

**Rollup:** `vmOperationCountGte10ms`, `safepointBeginCount`, `dataLossCount`

## Correlations block

Each key returns `status: ok` or `insufficient_data`.

| Key | Golden fixture output (when data exists) |
|-----|------------------------------------------|
| `cpuVsWall` | `offCpuFractionEstimatePercent`, `stacksWallOnly`, `stacksCpuOnly`, `stacksShared` |
| `heapVsRss` | `heapUsedSlopePerHour`, `heapVsRssGapMeanBytes` |
| `stackIntersections` | `cpuWallAlloc` stack keys |
| `compileVsNativeMalloc` | compilation vs malloc totals |
| `allocVsGc`, `jvmVsMachineCpu`, … | `status: ok` with note to use subsystem rollups |

**Markers** (timeseries): `gc_pause`, `cpu_throttle`, `data_loss` with `at`, `magnitude`, `subsystemRef`.
