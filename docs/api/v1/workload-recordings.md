# Workload and recordings

[← Index](README.md)

## `GET /api/v1/workload`

Workload index or detail depending on query parameters.

### Index (no parameters)

Lists namespaces → pods → containers with counts.

**Request**

```http
GET /api/v1/workload
```

**Response 200** — [samples/workload-index.json](samples/workload-index.json) (shape depends on ingested data; with golden fixtures you see `default` / `auth-cache-84cd9fbc49-fttcq` / `auth-cache`).

### Detail (all three identity parameters)

**Request**

```http
GET /api/v1/workload?namespace=default&pod=auth-cache-84cd9fbc49-fttcq&container=auth-cache
```

**Response 200** — [samples/workload-detail.json](samples/workload-detail.json)

```json
{
  "namespace": "default",
  "pod": "auth-cache-84cd9fbc49-fttcq",
  "container": "auth-cache",
  "recordingCount": 4,
  "chunkCount": 30,
  "start": "2026-09-12T12:43:20.137106Z",
  "end": "2026-09-13T09:49:33.919871779Z",
  "jvmVersion": "OpenJDK 64-Bit Server VM (21.0.12+8-LTS) for linux-aarch64 ...",
  "recordings": [
    {
      "filename": "profile-607.jfr",
      "sequence": 607,
      "bytes": 349990,
      "chunks": 6,
      "start": "2026-09-12T22:50:54.179452106Z",
      "end": "2026-09-12T22:51:54.209564499Z",
      "reportUrl": "/api/v1/namespaces/default/pods/auth-cache-84cd9fbc49-fttcq/containers/auth-cache/recordings/profile-607.jfr/report"
    }
  ]
}
```

### Partial filters

| Query | Behavior |
|-------|----------|
| `namespace` only | Pods/containers under namespace |
| `namespace` + `pod` | Containers for pod |
| `pod` or `container` alone | Search across namespaces |

## `GET /api/v1/recordings`

Lists rotation files with optional filters.

**Request**

```http
GET /api/v1/recordings?namespace=default&pod=auth-cache-84cd9fbc49-fttcq&container=auth-cache
```

**Response 200** — [samples/recordings-list.json](samples/recordings-list.json)

Each recording entry includes `start` / `end` from JFR chunk headers (UTC).
