# Aggregation APIs

[← Index](README.md)

`GET /api/v1/aggregations/{kind}` — algorithm helpers aligned with `analysis_algo.md`.

**Common request (golden full window)**

```http
GET /api/v1/aggregations/{kind}?namespace=default&pod=auth-cache-84cd9fbc49-fttcq&container=auth-cache&from=2026-09-12T00:00:00Z&to=2026-09-14T00:00:00Z
```

Shared response fields: `context` (envelope), `kind`, `stats`, optional `trends`, optional `top`.

## `gc-pauses`

**Sample** — [samples/aggregation-gc-pauses.json](samples/aggregation-gc-pauses.json)

```json
{
  "kind": "gc-pauses",
  "stats": {
    "count": 5,
    "sumMs": 1000.908156,
    "maxMs": 494.561566,
    "p50Ms": 104.062695,
    "p90Ms": 494.561566,
    "p95Ms": 494.561566,
    "p99Ms": 494.561566,
    "youngGcCount": 2
  }
}
```

Pause count, sum, max, and percentiles use only `jdk.GCPhasePause`. Nested `jdk.GCPhasePauseLevel1`–`4` events are returned separately as `stats.phaseBreakdown` (`name`, `level`, `count`, `sumMs`, `maxMs`) and are not added into the pause totals. `youngGcCount` still counts young GC events.

## `trends`

**Sample** — [samples/aggregation-trends.json](samples/aggregation-trends.json)

```json
{
  "kind": "trends",
  "stats": { "jdk.GCHeapSummarySlopePerHour": -6991880.5 },
  "trends": [
    {
      "name": "jdk.GCHeapSummary",
      "field": "heapUsed",
      "slopePerHour": -6991880.5,
      "points": [{ "timeNs": 1789230434419567370, "value": 383188976 }]
    }
  ]
}
```

## `allocations`

**Request**

```http
GET /api/v1/aggregations/allocations?...&limit=5
```

**Sample** — [samples/aggregation-allocations.json](samples/aggregation-allocations.json)

`top[]` entries: `group` `class` or `stack`, `bytes`, `count`, optional `stack` text.

## `samples`

| `flavor` | Event types used |
|----------|------------------|
| `cpu` (default) | `jdk.ExecutionSample`, `jdk.NativeMethodSample`, `profiler.ExecutionSample` |
| `wall` | `profiler.WallClockSample`, `profiler.ExecutionSample` |
| `all` | Union of cpu + wall |

**CPU (full window)** — [samples/aggregation-samples-cpu.json](samples/aggregation-samples-cpu.json)

**Wall (5 s slice)** — [samples/aggregation-samples-wall.json](samples/aggregation-samples-wall.json)

```http
GET /api/v1/aggregations/samples?namespace=default&pod=auth-cache-84cd9fbc49-fttcq&container=auth-cache&flavor=wall&limit=5&from=2026-09-12T22:50:54Z&to=2026-09-12T22:51:00Z
```

## `contention`

**Request**

```http
GET /api/v1/aggregations/contention?...&minDuration=20ms
```

Default `minDuration` is `20ms`. Types: `JavaMonitorEnter`, `JavaMonitorWait`, `ThreadPark`, `profiler.NativeLock`.

**Sample** — [samples/aggregation-contention.json](samples/aggregation-contention.json)

## `exceptions`

**Sample** — [samples/aggregation-exceptions.json](samples/aggregation-exceptions.json)

Uses `jdk.JavaExceptionThrow` when present (sparse in golden corpus).

## Invalid kind

```http
GET /api/v1/aggregations/not-a-kind?namespace=default&pod=x&container=y&last=5m
```

→ **400** `kind must be one of: gc-pauses, trends, allocations, samples, contention, exceptions`
