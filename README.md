# LocalData: Production-Grade Redis-Compatible Managed Data Platform

<p align="center">
  <img src="https://img.shields.io/badge/Java-21%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21" />
  <img src="https://img.shields.io/badge/Concurrency-Virtual_Threads-4B32C3?style=for-the-badge&logo=oracle&logoColor=white" alt="Virtual Threads" />
  <img src="https://img.shields.io/badge/Protocol-RESP2_%2B_REST-FF4438?style=for-the-badge&logo=redis&logoColor=white" alt="Redis Protocol" />
  <img src="https://img.shields.io/badge/Security-HMAC--SHA256-00C7B7?style=for-the-badge&logo=security&logoColor=white" alt="Security" />
  <img src="https://img.shields.io/badge/Storage-SQLite_WAL_%2B_CRC32-003B57?style=for-the-badge&logo=sqlite&logoColor=white" alt="SQLite WAL" />
  <img src="https://img.shields.io/badge/Tests-40%2F40_Passed-22C55E?style=for-the-badge&logo=githubactions&logoColor=white" alt="Tests" />
  <img src="https://img.shields.io/badge/License-Apache_2.0-blue?style=for-the-badge" alt="License" />
</p>

---

## 🌟 Overview

**LocalData** is a self-hostable, multi-tenant, Redis-compatible managed data platform. It bridges the gap between traditional Redis instances and modern cloud services like **Upstash** and **Redis Cloud**.

LocalData provides:
1. **REST API Gateway** on Port `8080` (powered by **Java 21 Virtual Threads**) for serverless and web client applications.
2. **RESP2 Redis TCP Server** on Port `6379` for existing Redis client libraries (`redis-cli`, `ioredis`, Jedis, Lettuce, redis-py).
3. **Physical Multi-Tenancy**: Complete tenant isolation at the keyspace layer (`p:{projectId}:d:{dbId}:{key}`). No client can ever inspect or mutate another client's data.
4. **Zero-Plaintext Security**: API keys (`ld_live_...`) are hashed using salted **HMAC-SHA256**. Secrets are displayed only once upon generation.
5. **Developer Dashboard**: An embedded modern single-page console at `http://localhost:8080/dashboard`.
6. **Official TypeScript / JavaScript SDK**: First-class developer experience with automatic deserialization, pipelining, and retries.

---

## 🏛️ System Architecture

The following diagram illustrates how clients connect through the dual-interface gateway down to the isolated storage engines and metadata repository:

```mermaid
flowchart TD
    subgraph Clients["Applications & Client Ecosystem"]
        A1["Node.js / TypeScript App\n(@localdata/client)"]
        A2["Web / Mobile / Serverless\n(HTTPS REST API)"]
        A3["Legacy Redis Client\n(redis-cli, ioredis, Jedis)"]
    end

    subgraph Gateway["Platform Ingress Gateway Layer"]
        G1["Virtual-Thread HTTP Server\n(Port 8080)"]
        G2["Developer Web Console\n(/dashboard)"]
        G3["RESP2 Redis TCP Server\n(Port 6379)"]
    end

    subgraph Security["Security & Governance Layer"]
        S1["HMAC-SHA256 Authenticator\n(Constant-Time Hash Compare)"]
        S2["Scoped Permission Filter\n(READ, WRITE, ADMIN)"]
        S3["Token Bucket Rate Limiter\n(Per-Project RPS & Burst)"]
        S4["Payload Size Enforcement\n(Max 512 KB)"]
    end

    subgraph Isolation["Tenant Isolation Engine"]
        T1["Namespacing Decorator\n(p:{projId}:d:{dbId}:{key})"]
        T2["Scoped Scanners & Metrics\n(SCAN, KEYS, DBSIZE, FLUSHDB)"]
    end

    subgraph Storage["Storage & Persistence Core"]
        E1["Embedded In-Memory Engine\n(ConcurrentHashMap, Active TTL)"]
        E2["CRC32 Binary Snapshotter\n(Atomic Safe Swap, .data)"]
        E3["SQLite Control Plane (WAL)\n(Projects, Accounts, Keys, Audit)"]
    end

    A1 -->|REST / HTTPS| G1
    A2 -->|REST / HTTPS| G1
    A3 -->|RESP2 TCP / AUTH| G3
    G2 -.->|Web Management API| G1

    G1 --> S1
    G3 --> S1

    S1 --> S2 --> S3 --> S4
    S4 --> T1
    T1 --> T2

    T2 --> E1
    E1 <-->|Background Persistence| E2
    S1 <-->|Key Verification & Audits| E3

    style Gateway fill:#1e293b,stroke:#3b82f6,stroke-width:2px,color:#f8fafc
    style Security fill:#1e293b,stroke:#ef4444,stroke-width:2px,color:#f8fafc
    style Isolation fill:#1e293b,stroke:#10b981,stroke-width:2px,color:#f8fafc
    style Storage fill:#1e293b,stroke:#8b5cf6,stroke-width:2px,color:#f8fafc
```

---

## 🔒 Multi-Tenant Data Isolation Model

Every read and write operation is tagged with the authenticated project ID. Keys are physically transformed before reaching the underlying engine:

```mermaid
flowchart LR
    subgraph ClientA["Tenant A (Project: proj_alpha)"]
        direction TB
        CA_KEY["Key: 'session:user_1'"]
        CA_API["API Key: ld_live_abc..."]
    end

    subgraph IsolationEngine["Tenant Isolation Decorator"]
        TRANSFORM_A["Prefix: p:proj_alpha:d:0:"]
        TRANSFORM_B["Prefix: p:proj_beta:d:0:"]
    end

    subgraph ClientB["Tenant B (Project: proj_beta)"]
        direction TB
        CB_KEY["Key: 'session:user_1'"]
        CB_API["API Key: ld_live_xyz..."]
    end

    subgraph PhysicalMemory["Underlying In-Memory Key Space"]
        MEM_A[("p:proj_alpha:d:0:session:user_1\nValue: {'user':'Alice'}")]
        MEM_B[("p:proj_beta:d:0:session:user_1\nValue: {'user':'Bob'}")]
    end

    CA_KEY --> TRANSFORM_A --> MEM_A
    CB_KEY --> TRANSFORM_B --> MEM_B

    classDef tenantA fill:#0369a1,stroke:#38bdf8,stroke-width:2px,color:#fff;
    classDef tenantB fill:#7c2d12,stroke:#f97316,stroke-width:2px,color:#fff;
    classDef storage fill:#1e293b,stroke:#94a3b8,stroke-width:2px,color:#fff;

    class ClientA,TRANSFORM_A,MEM_A tenantA;
    class ClientB,TRANSFORM_B,MEM_B tenantB;
    class PhysicalMemory storage;
```

> **Security Guarantee:**
> - Both tenants use the identical key name (`session:user_1`).
> - Tenant A's `SCAN`, `GET`, `DEL`, or `FLUSHDB` can **never** observe or affect Tenant B's keys.
> - ID spoofing is mathematically impossible because the project ID is derived from the authenticated cryptographic key hash, not from user request parameters.

---

## ⚡ End-to-End Request & Authentication Flow

```mermaid
sequenceDiagram
    autonumber
    actor Dev as Client / SDK
    participant GW as REST Gateway (:8080)
    participant Auth as Security Service
    participant RL as Rate Limiter
    participant DB as SQLite Meta (WAL)
    participant Store as Tenant Storage Engine

    Dev->>GW: POST /v1/keys/user:101 (Header: Bearer ld_live_...)
    GW->>Auth: Authenticate(ld_live_...)
    Auth->>Auth: Compute HMAC-SHA256(secret)
    Auth->>DB: Query cached key metadata by hash
    DB-->>Auth: Key Metadata (Project: proj_alpha, Scopes: [READ, WRITE])
    Auth-->>GW: SecurityContext(proj_alpha, db: 0)

    GW->>RL: CheckQuota(proj_alpha)
    alt Rate Limit Exceeded
        RL-->>GW: Rate Limit Exhausted
        GW-->>Dev: HTTP 429 Too Many Requests (Retry-After: 1)
    else Under Quota
        RL-->>GW: OK (Token Consumed)
        GW->>Store: set("user:101", value, options)
        Store->>Store: Key transformed to p:proj_alpha:d:0:user:101
        Store-->>GW: Success (true)
        GW-->>Dev: HTTP 200 {"success": true, "key": "user:101"}
    end
```

---

## 📊 Rate Limiting: Token Bucket Algorithm

Each project has its own dedicated in-memory token bucket configured with:
- **RPS (Refill Rate)**: Tokens added per second.
- **Burst Capacity**: Maximum burst size for high-traffic spikes.

```mermaid
flowchart TD
    REQ["Incoming Client Request"] --> CHECK{"Tokens Available in Bucket?"}
    CHECK -- Yes --> ALLOW["Consume 1 Token\nProcess Request with Virtual Thread"]
    CHECK -- No --> REJECT["Reject Request\nReturn HTTP 429 / RESP -ERR Rate limit exceeded\nHeader: Retry-After: 1"]

    REFILL["Refill Engine (Continuous Token Accumulation)"] -.->|Adds tokens up to Burst Capacity| CHECK
```

---

## 🖥️ Developer Dashboard Preview

The embedded developer dashboard is served automatically at **`http://localhost:8080/dashboard`**:

- **Real-Time System Metrics**: Total requests, cache hit ratio, average response latency, and memory footprint.
- **Project Provisioning**: Create namespaces with custom RPS, burst allowances, and storage quotas.
- **API Key Management**: Generate scoped keys (`read`, `write`, `admin`), view one-time plaintext secrets, and trigger immediate revocation.
- **Live Data Explorer**: Scan, inspect, update, and delete keys interactively within any tenant namespace.
- **Security Audit Logs**: Track administrative events (key generation, revocations, and configuration changes) with IP logs.

---

## 📦 Official TypeScript / JavaScript SDK

Install the SDK from `sdk/typescript`:

```bash
cd sdk/typescript
npm install
npm run build
```

### Usage Example

```typescript
import { LocalDataClient } from "@localdata/client";

// Initialize client with environment variables or explicit config
const client = new LocalDataClient({
  apiKey: process.env.LOCALDATA_API_KEY, // e.g. "ld_live_..."
  baseUrl: process.env.LOCALDATA_URL || "http://localhost:8080"
});

async function main() {
  // 1. Store structured JSON or plain text
  await client.set("user:101", {
    name: "Alice",
    role: "Lead Engineer",
    verified: true
  });

  // 2. Retrieve automatically deserialized objects
  const user = await client.get<{ name: string; role: string }>("user:101");
  console.log(`Hello, ${user?.name}!`);

  // 3. Set keys with TTL expiration
  await client.set("session:token_xyz", "active", { ex: 3600 }); // 1 hour
  const remainingSeconds = await client.ttl("session:token_xyz");

  // 4. Atomic counters
  const pageViews = await client.incr("stats:views", 1);
  console.log(`Page views: ${pageViews}`);

  // 5. Batch multi-key operations (MSET / MGET)
  await client.mset({
    "cfg:theme": "dark",
    "cfg:notifications": "enabled"
  });
  const configs = await client.mget(["cfg:theme", "cfg:notifications"]);

  // 6. High-throughput Atomic Pipeline (single network round-trip)
  const results = await client.pipeline([
    { cmd: "SET", args: ["batch:1", "apple"] },
    { cmd: "SET", args: ["batch:2", "banana"] },
    { cmd: "GET", args: ["batch:1"] },
    { cmd: "GET", args: ["batch:2"] }
  ]);
  console.log("Pipeline output:", results); // ["OK", "OK", "apple", "banana"]

  // 7. Tenant Key Scanning
  const scan = await client.scan({ pattern: "cfg:*" });
  console.log("Found keys:", scan.keys);

  // 8. Delete keys
  await client.del("user:101");
}

main().catch(console.error);
```

---

## 🔌 Redis Wire Protocol (RESP2 Compatibility)

Connect using standard Redis clients over TCP port `6379`. Simply authenticate with your API key:

```bash
# Connect with standard redis-cli
redis-cli -h 127.0.0.1 -p 6379

# Authenticate session to your tenant namespace
127.0.0.1:6379> AUTH ld_live_4d970bec106dbaa790138234c44b99f8fd89073131a6da7c
+OK

# Execute commands inside your isolated database
127.0.0.1:6379> PING
+PONG
127.0.0.1:6379> SET mykey "Hello World"
+OK
127.0.0.1:6379> GET mykey
"Hello World"
127.0.0.1:6379> INCRBY visit_counter 10
(integer) 10
127.0.0.1:6379> TTL mykey
(integer) -1
127.0.0.1:6379> DBSIZE
(integer) 2
```

### Supported Command Matrix

| Category | Commands Supported |
|---|---|
| **Connection & Auth** | `AUTH`, `PING`, `ECHO`, `QUIT` |
| **String & KV** | `GET`, `SET` (with `EX`, `PX`, `NX`, `XX`), `SETNX`, `SETEX`, `MGET`, `MSET`, `APPEND`, `STRLEN` |
| **Counters** | `INCR`, `DECR`, `INCRBY` |
| **Key & Expiration** | `DEL`, `EXISTS`, `EXPIRE`, `PEXPIRE`, `TTL`, `PTTL`, `PERSIST` |
| **Server & Inspection** | `SCAN`, `KEYS`, `DBSIZE`, `FLUSHDB`, `INFO`, `SAVE`, `TIME` |

---

## 🌐 REST API Gateway Reference

All endpoints accept standard HTTP requests. Authenticate via `Authorization: Bearer <API_KEY>`.

### Data Endpoints (`/v1/`)

| Method | Endpoint | Query / Body | Description |
|---|---|---|---|
| `GET` | `/v1/keys/{key}` | - | Retrieve key value |
| `POST` | `/v1/keys/{key}` | `?ex={sec}&nx=true`, body: `{value}` | Store key value with optional TTL / NX / XX |
| `DELETE` | `/v1/keys/{key}` | - | Delete key |
| `POST` | `/v1/keys/{key}/incr` | `?delta={int}` | Atomic counter increment / decrement |
| `POST` | `/v1/keys/{key}/expire` | `?seconds={int}` | Set TTL on existing key |
| `GET` | `/v1/keys/{key}/ttl` | - | Get remaining TTL (-1 = infinite, -2 = missing) |
| `POST` | `/v1/mget` | `{"keys": ["k1", "k2"]}` | Multi-key get |
| `POST` | `/v1/mset` | `{"entries": {"k1":"v1", ...}}` | Multi-key set |
| `GET` | `/v1/scan` | `?pattern={glob}&count={n}` | Scan tenant keyspace |
| `POST` | `/v1/pipeline` | `[{"cmd":"SET","args":[...]}, ...]` | Execute batch atomically in 1 round-trip |
| `GET` | `/v1/dbsize` | - | Get total keys in project database |
| `POST` | `/v1/flushdb` | - | Clear only the authenticated project's data |

### Management & Control Plane Endpoints (`/api/`)

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/health` | Health & system readiness probe |
| `GET` | `/api/projects` | List all provisioned projects |
| `POST` | `/api/projects` | Create a new isolated project namespace |
| `GET` | `/api/projects/{id}` | Inspect project quotas and configuration |
| `DELETE` | `/api/projects/{id}` | Delete project and wipe all data |
| `GET` | `/api/projects/{id}/keys` | List API keys (safe masked prefix) |
| `POST` | `/api/projects/{id}/keys` | Generate new API key with scoped permissions |
| `DELETE` | `/api/projects/{id}/keys/{kId}` | Immediately revoke API key |
| `GET` | `/api/metrics` | Retrieve live latency, RPS, hit ratio, and errors |
| `GET` | `/api/audit-logs` | Retrieve tamper-evident administrative audit log |
| `POST` | `/api/backups/snapshot` | Trigger on-demand CRC32 data snapshot |

---

## 🚀 Quick Start

### 1. Build and Run Standalone (JAR)

```bash
# Prerequisites: JDK 21+ and Maven 3.9+
mvn clean package -DskipTests

# Run LocalData platform
java -jar target/mini-redis-1.0-SNAPSHOT-all.jar
```

On initial startup, the console prints:
```text
╔══════════════════════════════════════════════════════════════╗
║          LocalData Managed Redis Platform v1.0.0             ║
╚══════════════════════════════════════════════════════════════╝
  Data Directory   : C:\My Projects\Redis\data
  REST & Dashboard : http://0.0.0.0:8080
  Redis RESP TCP   : 0.0.0.0:6379
  Storage Engine   : Embedded In-Memory (CRC32 Snapshotting)

🔑 BOOTSTRAP API KEY (Save this now!):
   ld_live_a1b2c3d4e5f6...
👉 Open Dashboard: http://localhost:8080/dashboard
```

### 2. Docker & Docker Compose

```bash
docker compose up -d
```

Check health:
```bash
curl http://localhost:8080/health
```

---

## 🛠️ Testing & Verification

The platform includes a test suite covering unit tests, tenant isolation, cryptographic hashing, concurrency, and rate limiting:

```bash
# Run automated JUnit 5 test suite (40 tests)
mvn test

# Run full live end-to-end integration suite (18 scenarios)
powershell -ExecutionPolicy Bypass -File .\verify_all.ps1

# Run TypeScript SDK live suite
cd sdk/typescript
npm test
```

---

## 📖 Additional Documentation

- [INSTALLATION.md](file:///C:/My%20Projects/Redis/INSTALLATION.md) — Comprehensive installation, Linux systemd service, Windows service, and production deployment guide.
- [docs/ARCHITECTURE.md](file:///C:/My%20Projects/Redis/docs/ARCHITECTURE.md) — Detailed internal architectural specifications.
- [docs/REST_API.md](file:///C:/My%20Projects/Redis/docs/REST_API.md) — Complete REST API specification with cURL examples.
- [docs/SECURITY.md](file:///C:/My%20Projects/Redis/docs/SECURITY.md) — Threat model, RBAC matrix, and cryptographic guarantees.
- [docs/RUNBOOK.md](file:///C:/My%20Projects/Redis/docs/RUNBOOK.md) — Operational runbooks, disaster recovery, and backup restoration.

---

## 📄 License

LocalData is distributed under the [Apache 2.0 License](file:///C:/My%20Projects/Redis/LICENSE).
