# Report and summary

[← Index](README.md)

JMC automated rules (`/report`) and raw per-type aggregates (`/summary`). Both support `recording=` **or** a time window, not both.

## `GET /api/v1/report`

**Request (time window, golden corpus)**

```http
GET /api/v1/report?namespace=default&pod=auth-cache-84cd9fbc49-fttcq&container=auth-cache&from=2026-09-12T00:00:00Z&to=2026-09-14T00:00:00Z&filter=gc
```

**Response 200** — truncated; full body: [samples/report-filter-gc.json](samples/report-filter-gc.json)

```json
{
  "namespace": "default",
  "pod": "auth-cache-84cd9fbc49-fttcq",
  "container": "auth-cache",
  "from": "2026-09-12T00:00:00Z",
  "to": "2026-09-14T00:00:00Z",
  "rules": {
    "some.rule.id": {
      "score": 75,
      "name": "...",
      "topic": "Garbage Collection",
      "description": "..."
    }
  }
}
```

### `filter` query

Case-insensitive substring match on rule `id`, `name`, or `topic`. Example: `filter=heap`, `filter=gc`.

### Rule scores

| Score | Meaning |
|-------|---------|
| `-1` | Not applicable (event type missing, e.g. profiler-only chunk) |
| `-3` | Ignored / failed |
| `0` | OK |
| `25` | Info |
| `75` | Warning |
| `0–100` | Finer score when rule supplies one |

Profiler-only windows often yield many `-1` scores; windows that include `jfrsync=default` (60 s chunks in the fixture) populate GC/heap rules.

## `GET /api/v1/summary`

Same window rules as `/report`. No JMC rules—per-event-type counts and field stats.

**Request**

```http
GET /api/v1/summary?namespace=default&pod=auth-cache-84cd9fbc49-fttcq&container=auth-cache&from=2026-09-12T00:00:00Z&to=2026-09-14T00:00:00Z&filter=heap
```

**Response 200** — [samples/summary-heap-filter.json](samples/summary-heap-filter.json) (large). Unfiltered: [samples/summary-full.json](samples/summary-full.json).

Top-level shape:

```json
{
  "namespace": "default",
  "pod": "auth-cache-84cd9fbc49-fttcq",
  "container": "auth-cache",
  "events": {
    "jdk.GCHeapSummary": {
      "name": "Heap Summary",
      "topic": "heap",
      "count": 10,
      "fields": { "heapUsed": { "min": 8626840, "max": 383188976, "avg": 190716268.8 } }
    }
  },
  "topics": {
    "heap": { "eventCount": 1234, "eventTypes": ["jdk.GCHeapSummary", "..."], "stats": {} }
  }
}
```

Omitted fields: addresses, raw JMC `Type(...)`, empty types, timestamp epochs, unbounded sentinels.

## Per-recording report/summary

```http
GET /api/v1/report?namespace=default&pod=auth-cache-84cd9fbc49-fttcq&container=auth-cache&recording=profile-607.jfr
```

No `from`/`to`/`last` with `recording`.
