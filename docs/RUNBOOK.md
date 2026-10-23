# Operations & Disaster Recovery Runbook: LocalData Platform

## 1. Quick Start & Deployment

### Running Standalone with Java 21+
```bash
# Package the fat jar
mvn clean package -DskipTests

# Run with custom environment variables
export HTTP_PORT=8080
export REDIS_PORT=6379
export DATA_DIR=/var/lib/localdata
export AUTH_PEPPER=$(openssl rand -hex 32)

java -jar target/mini-redis-1.0-SNAPSHOT-all.jar
```

### Running with Docker Compose
```bash
docker compose up -d
```
Inspect logs:
```bash
docker compose logs -f
```

---

## 2. Backup and Disaster Recovery Procedures

### Data Locations
The platform stores all state inside `DATA_DIR` (default: `./data`):
1. `platform_meta.db`: SQLite database storing accounts, projects, hashed API keys, and audit logs.
2. `redis.data`: RDB-style snapshot storing in-memory key-value data with CRC32 checksums.

### Step 1: Triggering an Online Consistent Snapshot
Before copying files, force a clean flush to disk:
```bash
curl -X POST http://localhost:8080/api/backups/snapshot
```

### Step 2: Performing Backup
```bash
BACKUP_DIR="/backups/localdata_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$BACKUP_DIR"

# 1. Backup SQLite metadata using SQLite safe backup or copy WAL
sqlite3 ./data/platform_meta.db ".backup '$BACKUP_DIR/platform_meta.db'"

# 2. Backup in-memory snapshot
cp ./data/redis.data "$BACKUP_DIR/redis.data"

tar -czvf "$BACKUP_DIR.tar.gz" -C "$BACKUP_DIR" .
rm -rf "$BACKUP_DIR"
echo "Backup saved to $BACKUP_DIR.tar.gz"
```

### Step 3: Restoring from Backup
1. Stop the platform process:
   ```bash
   docker compose down
   # or kill running java process
   ```
2. Replace the data directory files:
   ```bash
   tar -xzvf /backups/localdata_20261023_120000.tar.gz -C ./data/
   ```
3. Restart the platform:
   ```bash
   docker compose up -d
   ```
4. Verify system health:
   ```bash
   curl http://localhost:8080/health
   ```

---

## 3. Monitoring & Telemetry

### Health Check Endpoint
- **URL**: `http://localhost:8080/health`
- **Expected Status**: `200 OK`
- **Example Response**:
  ```json
  {
    "status": "UP",
    "version": "1.0.0",
    "uptimeMs": 84210,
    "jvmUptimeMs": 85100,
    "javaVersion": "21.0.4"
  }
  ```

### Live Metrics Endpoint
- **URL**: `http://localhost:8080/api/metrics`
- Key metrics to monitor:
  - `totalRequests`: Total requests handled since boot.
  - `hitRatio`: Cache hit efficiency (0.0 to 1.0). Target: > 0.85.
  - `averageLatencyMs`: Average response time. Target: < 2.0 ms.
  - `totalRateLimited`: Count of requests rejected due to rate limits.
  - `totalErrors`: Internal error count.

---

## 4. Troubleshooting Guide

### Issue: Client gets `401 INVALID_KEY`
- Verify the secret begins with `ld_live_`.
- Check if the key was revoked in the dashboard at `http://localhost:8080/dashboard`.
- Verify `AUTH_PEPPER` has not been changed between server restarts (changing the pepper invalidates previously hashed keys).

### Issue: Client gets `429 RATE_LIMITED`
- The project has exceeded its configured requests-per-second or burst capacity.
- Inspect `Retry-After` header in the HTTP response.
- Increase the project's `rateLimitRps` via `POST /api/projects` or the Dashboard.

### Issue: Data file corruption detected during boot
- If a server crashes during unbuffered write, the recovery mechanism automatically detects CRC32 checksum mismatch:
  ```text
  [miniredis] data file load failed (Checksum mismatch). Moving to redis.data.corrupt and starting empty.
  ```
- The corrupted file is preserved at `redis.data.corrupt` for forensic inspection, and the platform safely starts without crashing.
