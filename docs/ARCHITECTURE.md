# System Architecture: LocalData Managed Redis Data Platform

## 1. High-Level Overview

LocalData is a multi-tenant, cloud-native Redis-compatible managed data platform. It enables organizations to provision isolated data namespaces, manage cryptographically hashed API keys with scoped permissions, enforce rate limits and storage quotas, and perform sub-millisecond key-value operations via dual interfaces:
1. **REST API Gateway**: Powered by Java Virtual Threads for massive HTTP concurrency.
2. **RESP2 Wire Protocol TCP Server**: Allowing standard Redis CLI and existing Redis client libraries to connect natively.

```
┌────────────────────────────────────────────────────────┐
│               Client Applications & SDKs               │
│   (TypeScript SDK / cURL / redis-cli / ioredis / Jedis)│
└───────────────┬────────────────────────┬───────────────┘
                │ HTTP (Port 8080)       │ RESP2 TCP (Port 6379)
                ▼                        ▼
┌────────────────────────────────────────────────────────┐
│                 Platform Gateway Layer                 │
│  - Virtual-Thread REST HTTP Server                     │
│  - RESP2 Multi-Tenant TCP Socket Router                │
│  - Developer Web Dashboard (/dashboard)                │
└───────────────────────┬────────────────────────────────┘
                        │
                        ▼
┌────────────────────────────────────────────────────────┐
│            Security & Governance Filter                │
│  - HMAC-SHA256 Secret Verification                     │
│  - Scoped Permission Checking (READ, WRITE, ADMIN)     │
│  - Token Bucket Rate Limiter (Per-Project RPS/Burst)   │
│  - Payload Size Validator (Max 512 KB per value)       │
│  - Immutable Audit Logging (SQLite)                    │
└───────────────────────┬────────────────────────────────┘
                        │
                        ▼
┌────────────────────────────────────────────────────────┐
│             Tenant Isolation Decorator                 │
│  - Namespacing: p:{projectId}:d:{databaseId}:{key}    │
│  - Scoped KEYS, SCAN, DBSIZE, FLUSHDB                  │
└───────────────────────┬────────────────────────────────┘
                        │
                        ▼
┌────────────────────────────────────────────────────────┐
│             Pluggable Storage Engine                   │
│  - Embedded Concurrent In-Memory (Active TTL, CRC32 RDB│
│  - Optional External Redis/Valkey Cluster Driver       │
└────────────────────────────────────────────────────────┘
```

---

## 2. Multi-Tenancy & Namespace Isolation

Multi-tenancy is enforced physically at the storage layer via `TenantStorageEngine`.
- Every project has an immutable identifier (e.g. `proj_alpha`).
- Keys are stored internally as: `p:{projectId}:d:{databaseId}:{userKey}`.
- If Project A sets key `session:1` and Project B sets key `session:1`:
  - Project A's internal key: `p:proj_alpha:d:0:session:1`
  - Project B's internal key: `p:proj_beta:d:0:session:1`
- When Project A executes `SCAN` or `KEYS *`, the pattern is automatically rewritten as `p:proj_alpha:d:0:*`. Results are filtered to ensure they match the tenant prefix, and the prefix is stripped before returning to the client.
- When Project A executes `FLUSHDB`, only keys beginning with `p:proj_alpha:d:0:` are deleted. Project B's data is 100% untouched.

---

## 3. High Concurrency & Memory Management

- **Java Virtual Threads**: Every incoming HTTP request executes in a dedicated virtual thread, scaling to tens of thousands of concurrent connections with negligible memory overhead compared to traditional OS threads.
- **Lock-Free Concurrency**: Read operations on `EmbeddedStorageEngine` leverage `ConcurrentHashMap` with atomic access, avoiding coarse global locking.
- **Active & Lazy TTL Expiration**: Expired keys are evicted passively upon read access, and actively via a periodic background sampler (sampling 20 keys every 100ms).
- **RDB Persistence with CRC32 Validation**: Data is periodically snapshotted atomically to disk via a temporary file swap (`.tmp` → `.data`), guarded with a 32-bit CRC checksum. Corrupt data files are automatically isolated to `.corrupt` to ensure safe server startup.
