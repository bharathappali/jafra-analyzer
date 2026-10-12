# Bundle APIs

[← Index](README.md)

Playbooks that compose collect + aggregation calls (see `analysis_algo.md`).

## `GET /api/v1/bundles`

**Request**

```http
GET /api/v1/bundles
```

**Response 200** — [samples/bundles-catalog.json](samples/bundles-catalog.json)

```json
{
  "bundles": [
    { "id": "gc", "description": "GC pause and latency playbook: configuration, pause stats, and heap trends" },
    { "id": "memory", "description": "Memory OOM and leak playbook: ..." }
  ]
}
```

## `GET /api/v1/bundles/{id}`

Requires `namespace`, `pod`, `container`. Supports `recording=` **or** time window (same rules as `/report`). Optional: `limit`, `minDuration`, `flavor`, `names` (flags).

**Common request (golden window)**

```http
GET /api/v1/bundles/gc?namespace=default&pod=auth-cache-84cd9fbc49-fttcq&container=auth-cache&from=2026-09-12T00:00:00Z&to=2026-09-14T00:00:00Z&limit=5
```

**Response shape**

```json
{
  "context": { "recordings": ["profile-223.jfr", "..."], "chunks": 9 },
  "id": "gc",
  "description": "...",
  "sections": { }
}
```

### Bundle catalog

| `id` | Sections | Extra query params | Sample file |
|------|----------|-------------------|-------------|
| `version-check` | `jvm` | — | [bundle-version-check.json](samples/bundle-version-check.json) |
| `memory` | `jvm`, `trends`, `allocations`, `oomEvents` | `limit` | [bundle-memory.json](samples/bundle-memory.json) |
| `gc` | `jvm`, `gcConfiguration`, `gcPauses`, `trends` | `limit` | [bundle-gc.json](samples/bundle-gc.json) |
| `threads` | `jvm`, `contention`, `wallSamples` | `limit`, `minDuration` | [bundle-threads.json](samples/bundle-threads.json) |
| `performance` | `jvm`, `samples`, `allocations` | `limit`, `flavor` (`cpu` default) | [bundle-performance.json](samples/bundle-performance.json) |
| `jvm-config` | `jvm`, `flags`, `gcConfiguration` | `names` (comma flags) | [bundle-jvm-config.json](samples/bundle-jvm-config.json) |
| `exceptions` | `jvm`, `exceptions`, `exceptionStatistics` | `limit` | [bundle-exceptions.json](samples/bundle-exceptions.json) |
| `diagnostics` | `jvm`, `flags`, `dumpReason`, `container` | — | [bundle-diagnostics.json](samples/bundle-diagnostics.json) |
| `proactive` | `jvm`, `trends`, `gcPauses`, `allocations` | `limit` | [bundle-proactive.json](samples/bundle-proactive.json) |

### Section contents (golden `gc` bundle)

From [samples/bundle-gc.json](samples/bundle-gc.json):

| Section | Source API equivalent | Golden highlights |
|---------|----------------------|-------------------|
| `jvm` | `/jvm` | Java 21, `-Xmx378m`, async-profiler `jfrsync=default` |
| `gcConfiguration` | `/events?types=GCConfiguration,GCHeapConfiguration&view=summary` | Serial young/old, max heap ~396 MB |
| `gcPauses` | `/aggregations/gc-pauses` | 34 pauses, max ~495 ms |
| `trends` | `/aggregations/trends` | `heapUsed` slope −6.99 MB/hour |

### `performance` flavors

```http
GET /api/v1/bundles/performance?...&flavor=cpu
GET /api/v1/bundles/performance?...&flavor=wall
```

`wallSamples` section uses `flavor=wall` internally in the `threads` bundle.

### `threads` contention

```http
GET /api/v1/bundles/threads?...&minDuration=20ms
```

### Invalid bundle id

```http
GET /api/v1/bundles/not-defined?namespace=default&pod=x&container=y&last=5m
```

→ **400** `bundle id must be one of: ...`

### `oomEvents` / missing types

When event types are absent, `memory` bundle returns empty summary for `oomEvents` instead of failing:

```json
"oomEvents": { "view": "summary", "summary": {}, "typesRequested": "JavaErrorThrow,DumpReason,..." }
```
