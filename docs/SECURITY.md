# Security Model & Threat Mitigation: LocalData Platform

## 1. Secrets Management & API Key Security

### Key Anatomy
API keys are issued with a distinct prefix for easy scanning and detection:
- `ld_live_<hex_48_chars>` (Production keys)
- `ld_test_<hex_48_chars>` (Test keys)

### Cryptographic Storage Contract
- **Never Stored Plaintext**: The raw API secret is NEVER written to SQLite, logs, or disk.
- **HMAC-SHA256 Hashing**: Upon key generation, the secret is hashed using `HMAC-SHA256(secret, AUTH_PEPPER)` where `AUTH_PEPPER` is a 256-bit environment secret.
- **One-Time Display**: The raw secret is returned to the client **exactly once** in the key creation HTTP response. Subsequent API queries only reveal the safe identifier `keyPrefix` (e.g. `ld_live_7c4f8...`).
- **Immediate Revocation**: Revoking a key immediately marks `revoked_at = NOW()` in the relational store. Any subsequent incoming request is rejected with `401 INVALID_KEY`.

---

## 2. Multi-Tenant Threat Mitigation

### Threat 1: Cross-Tenant Key Collision
- **Scenario**: Tenant A creates key `users:count` and Tenant B creates key `users:count`.
- **Mitigation**: Keys are physically isolated using immutable internal prefixes: `p:{projectId}:d:{databaseId}:{key}`. Collisions across projects are mathematically impossible.

### Threat 2: Keyspace Snooping via SCAN / KEYS
- **Scenario**: Tenant A runs `KEYS *` or `SCAN 0` to discover other clients' data.
- **Mitigation**: `TenantStorageEngine` rewrites all pattern queries to `p:{projectId}:d:{databaseId}:{pattern}`. Any key from another tenant is strictly filtered out before the response is constructed.

### Threat 3: Hostile Database Flush
- **Scenario**: Tenant A sends `FLUSHDB` or calls `POST /v1/flushdb`.
- **Mitigation**: `FLUSHDB` is tenant-scoped. It executes `DELETE` exclusively on keys starting with that tenant's prefix. Other tenants experience zero loss of data or downtime.

---

## 3. Scoped Permissions (Least Privilege)

Every API key is configured with explicit scopes:
- `read`: Can only read keys, scan, and inspect TTL (`GET`, `MGET`, `EXISTS`, `TTL`, `SCAN`, `DBSIZE`).
- `write`: Can create, mutate, increment, and delete keys (`SET`, `MSET`, `DEL`, `INCR`, `EXPIRE`).
- `admin`: Can perform administrative operations (`FLUSHDB`, key rotation, project configuration).

---

## 4. Denial of Service (DoS) & Abuse Defenses

- **Token Bucket Rate Limiting**: Every project is configured with a maximum requests-per-second (RPS) and burst capacity. High-frequency abusive bursts trigger `429 RATE_LIMITED` with `Retry-After` headers.
- **Payload Size Guards**: Single values are capped at 512 KB (configurable via `MAX_PAYLOAD_SIZE_BYTES`) to prevent memory exhaustion attacks.
- **Max Clients Limit**: TCP server limits concurrent connections (default 1000) and closes saturated sockets cleanly.
