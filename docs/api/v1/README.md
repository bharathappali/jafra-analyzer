# Jafra Analyzer HTTP API v1

Reference for `jafra-analyzer` on port **8080** (`application/json`). All examples use the **golden JFR fixture** workload committed under `src/test/resources/jfr-fixtures/chunks/` (15 chunks, ~2.1 MiB, `auth-cache` on Kubernetes).

## Golden fixture identity (copy-paste)

| Field | Value |
|--------|--------|
| `namespace` | `default` |
| `pod` | `auth-cache-84cd9fbc49-fttcq` |
| `container` | `auth-cache` |
| Useful `from` / `to` (full corpus) | `2026-09-12T00:00:00Z` … `2026-09-14T00:00:00Z` |
| 5 s profiler slice | `2026-09-12T22:50:54Z` … `2026-09-12T22:51:00Z` |
| 60 s JDK periodic slice | `2026-09-12T22:50:54Z` … `2026-09-12T22:51:54Z` |

Base URL in examples: `http://127.0.0.1:8080`

## Index

1. [Operational endpoints](operational.md) — `/health`, `/api/v1/status`
2. [Workload and recordings](workload-recordings.md) — `/api/v1/workload`, `/api/v1/recordings`
3. [Report and summary](report-summary.md) — `/api/v1/report`, `/api/v1/summary`, nested redirects
4. [Collect APIs](collect.md) — `/api/v1/event-types`, `/api/v1/events`, `/api/v1/jvm`
5. [Aggregation APIs](aggregations.md) — `/api/v1/aggregations/{kind}`
6. [Analytics APIs](analytics.md) — `/api/v1/analytics/timeseries`, `/api/v1/analytics/daily_stats`, all subsystems
7. [Bundle APIs](bundles.md) — `/api/v1/bundles`, `/api/v1/bundles/{id}`
8. [Event type catalog (golden corpus)](event-types-catalog.md) — every type seen in the fixture window
9. [Shared query parameters and errors](shared-parameters.md)
10. [Sample responses](samples/README.md) — full JSON captured from the fixture (regenerate with `ApiDocumentationSampleGeneratorTest`)

## API families at a glance

| Family | Recording param | Default window | Primary use |
|--------|-----------------|----------------|-------------|
| Report / summary | Optional `recording=` | Latest **closed** file if no window | JMC rule scorecard + raw event aggregates |
| Collect / aggregation / analytics | **Not allowed** (400) | `last=15m` | Chunk-overlap timeseries analysis |
| Bundles | Optional `recording=` | `last=15m` when using time window | One-shot playbooks |

## Canonical vs nested URLs

**Canonical (flat):** query parameters `namespace`, `pod`, `container` on `/api/v1/report`, `/summary`, etc.

**Legacy nested:** `/api/v1/namespaces/{ns}/pods/{pod}/containers/{container}/report` → **HTTP 399** with `Location` header to the flat URL.

## Regenerating sample JSON

From `jafra-analyzer/`:

```bash
mvn -Dtest=ApiDocumentationSampleGeneratorTest test
```

Writes `docs/api/v1/samples/*.json` using the same golden chunks as CI.
