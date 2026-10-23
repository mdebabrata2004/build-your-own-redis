# REST API Reference: LocalData Platform

All requests to the `/v1/` data endpoints must include a valid API key:
- Header: `Authorization: Bearer <API_KEY>` or `X-API-Key: <API_KEY>`
- Query parameter: `?apiKey=<API_KEY>`

---

## 1. Data Endpoints (`/v1/`)

### `GET /v1/keys/{key}`
Retrieves the string or JSON value of a key.
- **Permission**: `read`
- **Response 200**:
  ```json
  {
    "success": true,
    "key": "user:123",
    "value": "{\"name\": \"Alice\"}"
  }
  ```
- **Response 404**:
  ```json
  {
    "success": false,
    "error": { "code": "KEY_NOT_FOUND", "message": "Key not found: user:123" }
  }
  ```

---

### `POST /v1/keys/{key}`
Sets a key's value with optional TTL and conditional checks.
- **Permission**: `write`
- **Query Parameters**:
  - `ex`: Expiration in seconds (integer)
  - `px`: Expiration in milliseconds (integer)
  - `nx`: Only set if not exists (`true`/`false`)
  - `xx`: Only set if exists (`true`/`false`)
- **Body**: Plain text or JSON payload (max 512 KB)
- **Response 200**:
  ```json
  {
    "success": true,
    "key": "user:123"
  }
  ```

---

### `DELETE /v1/keys/{key}`
Deletes a key.
- **Permission**: `write`
- **Response 200**:
  ```json
  {
    "success": true,
    "key": "user:123",
    "deleted": true
  }
  ```

---

### `POST /v1/keys/{key}/incr`
Atomically increments or decrements an integer counter.
- **Permission**: `write`
- **Query Parameter**: `delta` (default `1`, can be negative)
- **Response 200**:
  ```json
  {
    "success": true,
    "key": "pageviews",
    "value": 42
  }
  ```

---

### `POST /v1/keys/{key}/expire`
Sets a TTL expiration on an existing key.
- **Permission**: `write`
- **Query Parameter**: `seconds`
- **Response 200**:
  ```json
  {
    "success": true,
    "key": "session:temp",
    "applied": true
  }
  ```

---

### `GET /v1/keys/{key}/ttl`
Returns remaining TTL in seconds.
- **Permission**: `read`
- **Response 200**:
  ```json
  {
    "success": true,
    "key": "session:temp",
    "ttl": 284
  }
  ```

---

### `POST /v1/mget`
Batch retrieval of multiple keys.
- **Permission**: `read`
- **Body**: `{"keys": ["k1", "k2"]}`
- **Response 200**:
  ```json
  {
    "success": true,
    "results": {
      "k1": "val1",
      "k2": null
    }
  }
  ```

---

### `POST /v1/mset`
Batch setting of multiple key-value pairs.
- **Permission**: `write`
- **Body**: `{"entries": {"k1": "val1", "k2": "val2"}}`
- **Response 200**:
  ```json
  {
    "success": true,
    "count": 2
  }
  ```

---

### `GET /v1/scan`
Cursor-based pagination of keys in the current project.
- **Permission**: `read`
- **Query Parameters**: `cursor` (default `0`), `pattern` (default `*`), `count` (default `10`)
- **Response 200**:
  ```json
  {
    "success": true,
    "cursor": "10",
    "keys": ["user:1", "user:2"]
  }
  ```

---

### `POST /v1/pipeline`
Executes multiple commands in a single atomic network trip.
- **Permission**: Checked per command
- **Body**:
  ```json
  [
    {"cmd": "SET", "args": ["cart:1", "item_a"]},
    {"cmd": "INCR", "args": ["cart:1:count"]},
    {"cmd": "GET", "args": ["cart:1"]}
  ]
  ```
- **Response 200**:
  ```json
  {
    "success": true,
    "results": ["OK", 1, "item_a"]
  }
  ```

---

### `GET /v1/dbsize`
Returns key count in the current project namespace.
- **Permission**: `read`
- **Response 200**:
  ```json
  {
    "success": true,
    "dbsize": 142
  }
  ```

---

### `POST /v1/flushdb`
Deletes all keys belonging to the authenticated tenant project.
- **Permission**: `admin`
- **Response 200**:
  ```json
  {
    "success": true,
    "message": "Tenant database flushed successfully"
  }
  ```

---

## 2. Management Endpoints (`/api/`)

- `GET /api/projects`: List projects
- `POST /api/projects`: Create project (`{"name": "App", "slug": "app"}`)
- `GET /api/projects/{id}/keys`: List API keys (prefix only, never secrets)
- `POST /api/projects/{id}/keys`: Generate API key (one-time secret returned)
- `DELETE /api/projects/{id}/keys/{keyId}`: Revoke API key immediately
- `GET /api/metrics`: Live system metrics
- `GET /api/audit-logs`: Audit trail entries
- `POST /api/backups/snapshot`: Force RDB persistence save

---

## 3. Standard HTTP Error Codes

| Status Code | Error Code | Description |
| :--- | :--- | :--- |
| **400** | `BAD_REQUEST` | Missing or malformed parameters or JSON syntax error. |
| **401** | `UNAUTHORIZED` / `INVALID_KEY` | Missing, expired, or revoked API key. |
| **403** | `FORBIDDEN` | Key lacks required scope (e.g. read-only key attempting write). |
| **404** | `KEY_NOT_FOUND` / `NOT_FOUND` | Key or requested resource does not exist. |
| **413** | `PAYLOAD_TOO_LARGE` | Value size exceeds maximum payload limit (default 512 KB). |
| **429** | `RATE_LIMITED` | Project RPS or burst limit exceeded. Response includes `Retry-After`. |
| **500** | `INTERNAL_ERROR` | Server-side unexpected error. |
