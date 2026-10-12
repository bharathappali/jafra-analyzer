# API sample responses

[← API index](../README.md)

JSON captured from **golden JFR chunks** (`auth-cache` fixture) via `ApiDocumentationSampleGeneratorTest`.

## Regenerate

```bash
cd jafra-analyzer
mvn -Dtest=ApiDocumentationSampleGeneratorTest test
```

Each `*.json` is the raw HTTP body. Companion `*.meta.txt` holds the status code (expect **200**).

## Files

| File | Endpoint |
|------|----------|
| `health.json` | `GET /health` |
| `status.json` | `GET /api/v1/status` |
| `workload-index.json` | `GET /api/v1/workload` |
| `workload-detail.json` | `GET /api/v1/workload?namespace&pod&container` |
| `recordings-list.json` | `GET /api/v1/recordings?...` |
| `event-types.json` | `GET /api/v1/event-types?from=2026-09-12…&to=2026-09-14…` |
| `jvm.json`, `jvm-flags.json` | JVM collect |
| `events-jvm-summary.json` | `events` summary view |
| `events-cpu-series.json` | `events` series on `jdk.CPULoad` |
| `events-wall-records.json` | `profiler.WallClockSample` records |
| `summary-heap-filter.json`, `summary-full.json` | `/summary` |
| `report-filter-gc.json` | `/report?filter=gc` |
| `aggregation-*.json` | Each aggregation kind |
| `analytics-timeseries-profiler-window.json` | 6 s profiler analytics |
| `analytics-timeseries-jdk-window.json` | 60 s JDK analytics |
| `analytics-daily-stats.json` | Daily rollups (fixture window) |
| `analytics-*.excerpt.json` | Truncated copies for readable docs |
| `bundle-*.json` | Each playbook bundle |
| `bundles-catalog.json` | Bundle list |

## Query parameters used for captures

```
namespace=default
pod=auth-cache-84cd9fbc49-fttcq
container=auth-cache
from=2026-09-12T00:00:00Z
to=2026-09-14T00:00:00Z
```

Profiler-focused captures additionally use `from=2026-09-12T22:50:54Z&to=2026-09-12T22:51:00Z`.
