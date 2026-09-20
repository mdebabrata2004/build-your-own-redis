# Build Your Own Redis

A Redis-inspired in-memory database built from scratch in Java, featuring TCP networking, RESP2 protocol support, TTL-based expiration, snapshot persistence, and concurrent client handling.

> 🚧 **Work in Progress** — Actively evolving toward a production-oriented Redis architecture.

---

## ✨ Features

### Core Data Store
`SET` · `GET` · `DEL` · `EXISTS` · `APPEND` · `GETSET` · `FLUSHDB` · `DBSIZE`

### TTL & Expiration
`EXPIRE` · `TTL` · `PTTL` · `PERSIST` · Lazy expiration · Active expiration

### Atomic Operations
`INCR` · `INCRBY` · `DECR` · Thread-safe counter operations

### Conditional SET
`SET NX` · `SET XX` · `SET EX`

### Key Discovery
`KEYS` · `SCAN` · Pattern matching

### Persistence
Snapshot-based persistence with atomic file replacement, crash recovery, corrupt-file detection, and CRC validation

### Memory Management
Configurable maximum key count with automatic key eviction

### Networking
TCP server with persistent client connections, configurable worker thread pool, and connection limits

### Testing
JUnit 5 suite covering CRUD, TTL, persistence, corruption recovery, concurrency, eviction, and SCAN

---

## 🏗️ Architecture

```
Client
  │
  │ RESP
  ▼
TCP Server
  │
  ▼
RESP Decoder
  │
  ▼
Command Layer
  │
  ▼
Data Structures
  │
  ▼
Storage Engine
  │
  ├── Expiration
  ├── Persistence
  └── Memory Management
```

---

## 🧰 Tech Stack

| Layer       | Technology                      |
|-------------|---------------------------------|
| Language    | Java 21                         |
| Build       | Maven 3.9+                      |
| Testing     | JUnit 5, Mockito, AssertJ       |
| Logging     | SLF4J + Logback                 |
| Networking  | TCP/IP sockets, Java NIO        |
| Concurrency | Java concurrent collections     |

---

## 📁 Project Structure

```
src/
├── main/java/com/miniredis/
│   ├── Main.java
│   ├── client/
│   │   ├── RedisClient.java
│   │   └── ClientMain.java
│   ├── command/
│   │   ├── Command.java
│   │   └── CommandHandler.java
│   ├── server/
│   │   ├── RedisServer.java
│   │   └── ClientHandler.java
│   └── store/
│       └── DataStore.java
└── test/java/com/miniredis/
    └── DataStoreTest.java
```

---

## ⚙️ Requirements

- JDK 21+
- Maven 3.9+

Verify your environment:

```bash
java -version
javac -version
mvn -version
```

---

## 🔨 Build

```bash
# Clone the repository
git clone https://github.com/mdebabrata2004/build-your-own-redis.git
cd build-your-own-redis

# Compile
mvn clean compile

# Run tests
mvn test

# Package
mvn package
```

---

## ▶️ Running the Server

```bash
java -jar target/mini-redis-1.0-SNAPSHOT-all.jar
```

Default configuration:

```
╔══════════════════════════════════════╗
║          Mini Redis Server           ║
╚══════════════════════════════════════╝

Bind       : 0.0.0.0:6379
Threads    : 32
Max clients: 1000
```

---

## 💻 Usage

### Supported Commands

```
PING
SET <key> <value>
SET <key> <value> EX <seconds>
GET <key>
DEL <key>
EXISTS <key>
APPEND <key> <value>
INCR <key>
INCRBY <key> <amount>
DECR <key>
TTL <key>
KEYS <pattern>
SCAN <cursor>
DBSIZE
FLUSHDB
```

### Example Session

```
localhost:6379> PING
PONG

localhost:6379> SET name "Rahul"
OK

localhost:6379> GET name
Rahul

localhost:6379> SET session abc123 EX 60
OK

localhost:6379> TTL session
(integer) 59

localhost:6379> INCR counter
(integer) 1

localhost:6379> INCRBY counter 10
(integer) 11

localhost:6379> DBSIZE
(integer) 3
```

---

## 🧪 Test Coverage

```
Tests run: 20  |  Failures: 0  |  Errors: 0  |  Skipped: 0

BUILD SUCCESS
```

Coverage includes: basic CRUD, missing/invalid keys, TTL expiration, persistence and reload,
corrupt file recovery, `SET NX` / `SET XX`, atomic counters, concurrent `INCR`, `APPEND`,
`KEYS`, `SCAN`, key eviction, flush, and database size.

```bash
mvn test
```

---

## 🛣️ Roadmap

### ✅ Phase 1 — Core Engine
- [x] TCP server with persistent client connections
- [x] Basic commands, TTL, atomic counters
- [x] Snapshot persistence and corruption recovery
- [x] Concurrent client support and unit tests

### 🔄 Phase 2 — Redis Protocol *(current focus)*
- [ ] RESP2 decoder and encoder
- [ ] Bulk strings, arrays, error responses
- [ ] Protocol integration tests

### 📦 Phase 3 — Data Structures
- [ ] Strings, Hashes, Lists, Sets, Sorted Sets

### ⚙️ Phase 4 — Server Features
- [ ] Transactions, Pub/Sub, blocking commands
- [ ] Configuration system, authentication, ACL, TLS

### 🏭 Phase 5 — Production Engineering
- [ ] AOF persistence and rewrite, recovery strategy
- [ ] Metrics, structured logging, benchmarking, JFR profiling, load/failure testing

### 🌐 Phase 6 — Distributed Redis
- [ ] Replication, primary/replica architecture
- [ ] Failover, sharding, cluster/slot architecture

---

## 🎯 Why This Project?

Rather than treating Redis as a black box, this project is an engineering exercise in
understanding the systems beneath it — TCP servers, concurrent client handling, in-memory
storage, TTL expiration, atomic operations, persistence, database protocol design, failure
recovery, and distributed system scaling.

---

## 🤝 Contributing

This is a personal learning project, but ideas, bug reports, and architectural suggestions
are welcome. Feel free to open an issue.

---

## 📜 License

This project is currently intended as a personal learning and engineering exercise.
