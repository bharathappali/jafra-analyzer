# Shared query parameters and errors

[← Index](README.md)

## Workload identity (required for analysis)

| Parameter | Required | Description |
|-----------|----------|-------------|
| `namespace` | Yes | Kubernetes namespace |
| `pod` | Yes | Pod name (not UID) |
| `container` | Yes | Container name |

Missing any → **400** `{"status":"bad_request","error":"namespace is required"}` (or `pod` / `container`).

## Time windows

Only **one** window family per request. Combining `last` with `from`/`to`/`before`/`after` → **400**.

| Parameter | Semantics |
|-----------|-----------|
| `last` | Wall-clock duration ending **now** (`5m`, `15m`, `1h`, `1d`, max 7d). Default **`15m`** on collect, aggregation, analytics timeseries when nothing else is set. |
| `from` + `to` | Absolute ISO-8601 UTC range; event `startTime` clipped inclusively. |
| `from` alone | From timestamp through now. |
| `to` alone | From earliest stored chunk through `to`. |
| `before` | Everything ending before timestamp. |
| `after` | Everything starting after timestamp. |

**Report/summary without a window:** analyzes the latest **closed** rotation (successor exists). Live file excluded.

**Timeseries APIs without overlapping chunks:** **404**

```json
{
  "status": "not_found",
  "namespace": "default",
  "pod": "auth-cache-84cd9fbc49-fttcq",
  "container": "auth-cache",
  "from": "2030-01-01T00:00:00Z",
  "to": "2030-01-02T00:00:00Z",
  "error": "no recordings overlap the requested window"
}
```

## `recording` parameter

| APIs | `recording` |
|------|-------------|
| `/api/v1/report`, `/summary`, `/bundles/{id}` | Allowed; **cannot** combine with time window |
| `/api/v1/event-types`, `/events`, `/aggregations/*`, `/analytics/*` | **400** if set |

## Analysis envelope (`context`)

Most analysis responses include:

| Field | Meaning |
|-------|---------|
| `start` / `end` | Coverage of loaded chunk data (UTC) |
| `from` / `to` | Requested window |
| `bytes` | Bytes read from chunk payloads |
| `chunks` | Chunk files loaded |
| `recordings` | Rotation filenames included |
| `generatedAt` | Server timestamp |

## HTTP status summary

| Code | When |
|------|------|
| **200** | Success |
| **399** | Nested path; follow `Location` |
| **400** | Invalid parameters, unknown aggregation kind, invalid bundle id |
| **404** | No data for workload or window |
| **422** | JFR read/analysis failure (`status: unavailable`) |
