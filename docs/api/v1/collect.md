# Collect APIs

[← Index](README.md)

Require `namespace`, `pod`, `container`. Default window **`last=15m`** if omitted. **`recording` → 400**.

## `GET /api/v1/event-types`

Lists JFR types present in the window with topic grouping.

**Request (golden full window)**

```http
GET /api/v1/event-types?namespace=default&pod=auth-cache-84cd9fbc49-fttcq&container=auth-cache&from=2026-09-12T00:00:00Z&to=2026-09-14T00:00:00Z
```

**Response 200** — [samples/event-types.json](samples/event-types.json). See [event-types-catalog.md](event-types-catalog.md) for a table of all types in that sample.

```json
{
  "context": { "chunks": 15, "bytes": 2155388, "from": "2026-09-12T00:00:00Z", "to": "2026-09-14T00:00:00Z" },
  "types": [
    { "id": "profiler.WallClockSample", "name": "Wall Clock Sample", "topic": "cpu", "count": 1802 },
    { "id": "jdk.CPULoad", "name": "CPU Load", "topic": "cpu", "count": 240 }
  ]
}
```

## `GET /api/v1/jvm`

Single `jdk.JVMInformation` row when present.

**Request**

```http
GET /api/v1/jvm?namespace=default&pod=auth-cache-84cd9fbc49-fttcq&container=auth-cache&from=2026-09-12T00:00:00Z&to=2026-09-14T00:00:00Z
```

**Response 200** — [samples/jvm.json](samples/jvm.json)

## `GET /api/v1/jvm/flags`

Merged JVM flags from `*Flag` events.

**Request**

```http
GET /api/v1/jvm/flags?namespace=default&pod=auth-cache-84cd9fbc49-fttcq&container=auth-cache&names=MaxHeapSize,UseG1GC&from=2026-09-12T00:00:00Z&to=2026-09-14T00:00:00Z
```

**Response 200** — [samples/jvm-flags.json](samples/jvm-flags.json)

## `GET /api/v1/events`

| Parameter | Required | Values |
|-----------|----------|--------|
| `types` | Yes | Comma-separated full ids (`jdk.CPULoad`) or suffixes (`JVMInformation`, `ExecutionSample`) |
| `view` | No | `summary` (default), `records`, `series` |
| `field` | For `series` | Numeric field on the type (e.g. `machineTotal` on `jdk.CPULoad`) |
| `limit` | No | Records: default 50, max 500. Series: default 500, max 5000 |
| `minDuration` | No | Records only; filters by duration/park/blocked fields |

### View: `summary`

**Request**

```http
GET /api/v1/events?namespace=default&pod=auth-cache-84cd9fbc49-fttcq&container=auth-cache&types=JVMInformation&view=summary&from=2026-09-12T00:00:00Z&to=2026-09-14T00:00:00Z
```

**Response** — [samples/events-jvm-summary.json](samples/events-jvm-summary.json)

### View: `series` (exactly one type)

**Request**

```http
GET /api/v1/events?namespace=default&pod=auth-cache-84cd9fbc49-fttcq&container=auth-cache&types=jdk.CPULoad&view=series&field=machineTotal&limit=10&from=2026-09-12T22:50:54Z&to=2026-09-12T22:51:54Z
```

**Response** — [samples/events-cpu-series.json](samples/events-cpu-series.json)

```json
{
  "view": "series",
  "field": "machineTotal",
  "series": {
    "jdk.CPULoad": [
      { "timeNs": 1789253454179454210, "value": 21.86 }
    ]
  }
}
```

### View: `records`

**Request (profiler wall samples, 5 s slice)**

```http
GET /api/v1/events?namespace=default&pod=auth-cache-84cd9fbc49-fttcq&container=auth-cache&types=profiler.WallClockSample&view=records&limit=3&from=2026-09-12T22:50:54Z&to=2026-09-12T22:51:00Z
```

**Response** — [samples/events-wall-records.json](samples/events-wall-records.json)

```json
{
  "view": "records",
  "records": {
    "profiler.WallClockSample": [
      {
        "startTime": 1789217000147853769,
        "sampledThread": "main",
        "state": "STATE_SLEEPING",
        "samples": 1
      }
    ]
  }
}
```

### Common `types` values in the golden corpus

| `types=` | Fixture window | Notes |
|----------|----------------|-------|
| `JVMInformation` | Full | JVM 21 aarch64, agent args |
| `jdk.CPULoad` | 60 s JDK chunks | `jvmUser`, `jvmSystem`, `machineTotal` |
| `profiler.WallClockSample` | 5 s profiler chunks | `state`, `sampledThread`, stacks |
| `jdk.ExecutionSample` | Mixed | CPU stacks, often VM GC frames |
| `jdk.ObjectAllocationInNewTLAB` | Profiler chunks | `objectClass`, `allocationSize` |
| `jdk.GCPhasePause` | 60 s chunks | Pause durations |
| `jdk.ContainerCPUThrottling` | 60 s chunks | Cgroup throttling |
| `profiler.Malloc`, `profiler.Free` | Profiler chunks | Native churn |

**Errors:** missing `types` → 400; `series` with multiple types → 400; unknown field → 400.
