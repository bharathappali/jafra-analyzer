# Operational endpoints

[← Index](README.md)

## `GET /health`

Liveness for Kubernetes.

**Request**

```http
GET /health
```

**Response 200** — see [samples/health.json](samples/health.json)

```json
{
  "status": "UP",
  "checks": []
}
```

## `GET /api/v1/status`

Analyzer store statistics.

**Request**

```http
GET /api/v1/status
```

**Response 200** — [samples/status.json](samples/status.json)

```json
{
  "durableChunks": 15,
  "stitchedBytes": 0
}
```

(`stitchedBytes` is always `0`; chunks are read in stitch order without materializing a combined file.)
