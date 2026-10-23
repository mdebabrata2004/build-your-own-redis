# LocalData Platform: Installation & Deployment Guide

This document provides complete, step-by-step instructions for installing, configuring, hardening, and operating the **LocalData Managed Redis Platform** across **Linux**, **Windows**, **macOS**, and **Docker** environments.

---

## 📋 Table of Contents
- [1. System Requirements](#1-system-requirements)
- [2. Installation Options](#2-installation-options)
  - [Option A: Build from Source (Recommended)](#option-a-build-from-source-recommended)
  - [Option B: Docker & Docker Compose](#option-b-docker--docker-compose)
  - [Option C: Pre-Built Fat JAR](#option-c-pre-built-fat-jar)
- [3. Configuration Reference](#3-configuration-reference)
- [4. Linux Production Deployment (Systemd)](#4-linux-production-deployment-systemd)
- [5. Windows Production Deployment (Service)](#5-windows-production-deployment-service)
- [6. Reverse Proxy & TLS/HTTPS Hardening](#6-reverse-proxy--tlshttps-hardening)
- [7. Verification & Smoke Testing](#7-verification--smoke-testing)
- [8. Backup & Disaster Recovery](#8-backup--disaster-recovery)
- [9. Troubleshooting & FAQ](#9-troubleshooting--faq)

---

## 1. System Requirements

### Hardware Requirements
| Resource | Minimum (Development) | Recommended (Production) |
|---|---|---|
| **CPU** | 1 vCPU / Core | 2 to 4 vCPUs |
| **RAM** | 512 MB | 2 GB to 8 GB (depending on in-memory dataset) |
| **Disk** | 1 GB Free Disk Space | Fast NVMe / SSD for SQLite WAL and CRC32 snapshots |
| **Network** | 100 Mbps | 1 Gbps+ low-latency network |

### Software Prerequisites
- **Java Runtime Environment**: **OpenJDK 21** or higher (required for Java Virtual Threads).
- **Build Tool**: **Apache Maven 3.9+** (if compiling from source).
- **Node.js**: **v18+** (only if building and using the TypeScript SDK).
- **Container Runtime**: **Docker 24+** & **Docker Compose v2+** (optional).

Verify your installed versions:
```bash
java -version
mvn -version
```

---

## 2. Installation Options

### Option A: Build from Source (Recommended)

1. **Clone the repository**:
   ```bash
   git clone https://github.com/your-org/mini-redis.git
   cd mini-redis
   ```

2. **Run automated test suite**:
   ```bash
   mvn test
   ```
   *All 40 unit and integration tests should pass.*

3. **Package the shaded self-contained Uber-JAR**:
   ```bash
   mvn clean package -DskipTests
   ```
   This generates `target/mini-redis-1.0-SNAPSHOT-all.jar` containing all bundled dependencies (Jackson, SQLite JDBC, Logback, SLF4J).

4. **Launch LocalData**:
   ```bash
   java -jar target/mini-redis-1.0-SNAPSHOT-all.jar
   ```

---

### Option B: Docker & Docker Compose

For containerized environments, the repository includes a multi-stage `Dockerfile` and `docker-compose.yml`.

1. **Start LocalData in background mode**:
   ```bash
   docker compose up -d --build
   ```

2. **Inspect running container logs**:
   ```bash
   docker compose logs -f localdata
   ```

3. **Stop container**:
   ```bash
   docker compose down
   ```

Data is persisted on the host machine in the mapped `./data` volume.

---

### Option C: Pre-Built Fat JAR

If you already have `mini-redis-1.0-SNAPSHOT-all.jar`:
```bash
# Create data storage directory
mkdir -p data

# Run JAR
java -Xms512m -Xmx4g -jar mini-redis-1.0-SNAPSHOT-all.jar
```

---

## 3. Configuration Reference

LocalData supports zero-configuration defaults and can be customized via **Environment Variables** or system properties.

| Environment Variable | Default Value | Description |
|---|---|---|
| `LOCALDATA_HTTP_PORT` | `8080` | REST API Gateway & Dashboard port |
| `LOCALDATA_REDIS_PORT` | `6379` | Redis Wire Protocol (RESP2) TCP port |
| `LOCALDATA_BIND_ADDRESS` | `0.0.0.0` | Network interface to bind (`127.0.0.1` for local only) |
| `LOCALDATA_DATA_DIR` | `./data` | Directory storing SQLite metadata and RDB snapshots |
| `LOCALDATA_MAX_PAYLOAD_SIZE` | `524288` | Maximum value payload size in bytes (default 512 KB) |
| `LOCALDATA_DEFAULT_RATE_LIMIT_RPS` | `100` | Default requests-per-second limit for new projects |
| `LOCALDATA_DEFAULT_RATE_LIMIT_BURST`| `200` | Default burst token allowance for new projects |
| `LOCALDATA_STORAGE_ENGINE` | `embedded` | Storage engine (`embedded` or `redis`) |

### Example: Custom Launch
```bash
export LOCALDATA_HTTP_PORT=9000
export LOCALDATA_REDIS_PORT=6380
export LOCALDATA_DATA_DIR=/var/lib/localdata
java -jar target/mini-redis-1.0-SNAPSHOT-all.jar
```

---

## 4. Linux Production Deployment (Systemd)

Follow these steps to run LocalData as a production-grade Linux daemon with auto-restart on boot.

### 1. Create a Dedicated Service User
```bash
sudo useradd -r -s /bin/false localdata
```

### 2. Set Up Installation and Data Directories
```bash
sudo mkdir -p /opt/localdata /var/lib/localdata
sudo cp target/mini-redis-1.0-SNAPSHOT-all.jar /opt/localdata/localdata.jar
sudo chown -R localdata:localdata /opt/localdata /var/lib/localdata
sudo chmod 750 /var/lib/localdata
```

### 3. Create Systemd Service File
Create `/etc/systemd/system/localdata.service`:
```ini
[Unit]
Description=LocalData Managed Redis Platform
After=network.target

[Service]
Type=simple
User=localdata
Group=localdata
WorkingDirectory=/var/lib/localdata
Environment="LOCALDATA_DATA_DIR=/var/lib/localdata"
Environment="LOCALDATA_BIND_ADDRESS=127.0.0.1"
Environment="LOCALDATA_HTTP_PORT=8080"
Environment="LOCALDATA_REDIS_PORT=6379"
ExecStart=/usr/bin/java -Xms1g -Xmx4g --enable-preview -jar /opt/localdata/localdata.jar
Restart=always
RestartSec=5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
```

### 4. Enable and Start the Service
```bash
sudo systemctl daemon-reload
sudo systemctl enable --now localdata
sudo systemctl status localdata
```

### 5. Monitor Logs
```bash
sudo journalctl -u localdata -f
```

---

## 5. Windows Production Deployment (Service)

### Method 1: Running as a Background Service via NSSM
1. Download **NSSM** (Non-Sucking Service Manager) from [nssm.cc](https://nssm.cc/).
2. Run in administrative PowerShell:
   ```powershell
   nssm install LocalDataService "C:\Program Files\Java\jdk-21\bin\java.exe" "-jar C:\LocalData\mini-redis-1.0-SNAPSHOT-all.jar"
   nssm set LocalDataService AppDirectory "C:\LocalData"
   nssm set LocalDataService Start SERVICE_AUTO_START
   nssm start LocalDataService
   ```

### Method 2: Standalone PowerShell Launch
```powershell
Set-Location "C:\My Projects\Redis"
java -jar target/mini-redis-1.0-SNAPSHOT-all.jar
```

---

## 6. Reverse Proxy & TLS/HTTPS Hardening

For production internet-facing deployments, never expose port `8080` directly. Place LocalData behind **Nginx** or **Caddy** with automated SSL/TLS certificates.

### Nginx Configuration (`/etc/nginx/sites-available/localdata`)
```nginx
server {
    listen 80;
    server_name data.yourdomain.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name data.yourdomain.com;

    ssl_certificate /etc/letsencrypt/live/data.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/data.yourdomain.com/privkey.pem;

    client_max_body_size 2M;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # WebSockets / virtual threads connection timeouts
        proxy_read_timeout 90s;
        proxy_send_timeout 90s;
    }
}
```

### Caddy Configuration (`Caddyfile`)
```caddy
data.yourdomain.com {
    reverse_proxy 127.0.0.1:8080
}
```

### Redis TCP Port Hardening (Port 6379)
Port `6379` should only be accessible within your private VPC network. Restrict external access with `ufw`:
```bash
# Allow local applications & private VPC subnet only
sudo ufw allow from 10.0.0.0/16 to any port 6379 proto tcp
sudo ufw deny 6379/tcp
```

---

## 7. Verification & Smoke Testing

Run the included end-to-end verification script or test endpoints manually.

### Automated Test Script (Windows / Linux PowerShell)
```powershell
powershell -ExecutionPolicy Bypass -File .\verify_all.ps1
```

### Manual cURL Smoke Test
1. **Health Check**:
   ```bash
   curl http://localhost:8080/health
   # Returns: {"status":"UP","uptimeMs":...}
   ```

2. **Access Web Dashboard**:
   Open **`http://localhost:8080/dashboard`** in your browser.

3. **Store a Key via REST API**:
   ```bash
   curl -X POST "http://localhost:8080/v1/keys/user:test" \
     -H "Authorization: Bearer <YOUR_API_KEY>" \
     -H "Content-Type: application/json" \
     -d '{"name": "Alice", "role": "Architect"}'
   ```

4. **Retrieve Key**:
   ```bash
   curl -H "Authorization: Bearer <YOUR_API_KEY>" "http://localhost:8080/v1/keys/user:test"
   ```

5. **Test Redis TCP Client**:
   ```bash
   redis-cli -h 127.0.0.1 -p 6379
   127.0.0.1:6379> AUTH <YOUR_API_KEY>
   127.0.0.1:6379> PING
   +PONG
   ```

---

## 8. Backup & Disaster Recovery

LocalData separates data storage into two components:
1. **Metadata Control Plane**: `./data/platform_meta.db` (SQLite in WAL mode).
2. **Key-Value Data Engine**: `./data/miniredis.data` (CRC32 binary snapshot).

### Taking a Consistent Online Backup

1. **Trigger snapshot via API**:
   ```bash
   curl -X POST http://localhost:8080/api/backups/snapshot
   ```

2. **Backup SQLite Database**:
   ```bash
   # Safe backup while service is running (using sqlite3 CLI)
   sqlite3 ./data/platform_meta.db ".backup ./backups/platform_meta_$(date +%Y%m%d_%H%M%S).db"

   # Copy key-value snapshot
   cp ./data/miniredis.data ./backups/miniredis_$(date +%Y%m%d_%H%M%S).data
   ```

### Restoring from Backup
1. Stop the LocalData service:
   ```bash
   sudo systemctl stop localdata
   ```
2. Restore the files into `./data/`:
   ```bash
   cp /backups/platform_meta_20261023.db /var/lib/localdata/platform_meta.db
   cp /backups/miniredis_20261023.data /var/lib/localdata/miniredis.data
   ```
3. Restart the service:
   ```bash
   sudo systemctl start localdata
   ```

---

## 9. Troubleshooting & FAQ

### Issue: `Address already in use: bind` on Port 8080 or 6379
**Cause:** Another process or previous instance is running.
**Fix (Windows):**
```powershell
Get-NetTCPConnection -LocalPort 8080,6379 -ErrorAction SilentlyContinue | Select-Object OwningProcess
Stop-Process -Id <PID> -Force
```
**Fix (Linux / macOS):**
```bash
sudo lsof -i :8080
sudo lsof -i :6379
sudo kill -9 <PID>
```

---

### Issue: `UnsupportedClassVersionError: ... class file version 65.0`
**Cause:** You are running Java 17 or older. LocalData requires **Java 21** or newer.
**Fix:**
```bash
sudo apt install openjdk-21-jdk  # Ubuntu/Debian
brew install openjdk@21          # macOS
```

---

### Issue: `403 FORBIDDEN: Permission denied for action WRITE`
**Cause:** The API key used does not have the `write` scope.
**Fix:** Open the dashboard at `/dashboard` -> **API Keys**, and generate a key with `read,write` or `admin` scopes.

---

### Issue: `429 TOO_MANY_REQUESTS`
**Cause:** Project exceeded its configured Requests-Per-Second (RPS) quota.
**Fix:** The response includes `Retry-After: 1`. In the dashboard, edit the project configuration to increase the RPS or burst allowance.
