Build Your Own Redis
A Redis-inspired in-memory database built from scratch in Java with TCP networking, persistence, TTL, concurrency, and RESP2 support.
> 🚧 **Work in Progress**
>
> This project is actively evolving toward a production-oriented Redis architecture.
---
🚀 Features
Core Data Store
`SET`
`GET`
`DEL` / `DELETE`
`EXISTS`
`APPEND`
`GETSET`
`FLUSHDB`
`DBSIZE`
TTL & Expiration
`EXPIRE`
`TTL`
`PTTL`
`PERSIST`
Lazy expiration
Active expiration
Atomic Operations
`INCR`
`INCRBY`
`DECR`
Concurrent atomic counter operations
Conditional SET
`SET NX`
`SET XX`
`SET EX`
Key Discovery
`KEYS`
`SCAN`
Pattern matching
Persistence
Snapshot-based persistence
Atomic file replacement
Persistence recovery
Corrupt data-file detection
CRC validation
Memory Management
Configurable maximum key count
Key eviction
Networking
TCP server
Persistent client connections
Multiple concurrent clients
Configurable worker threads
Connection limits
Testing
JUnit 5
CRUD tests
TTL tests
Persistence tests
Corruption recovery tests
Concurrency tests
Eviction tests
SCAN tests
---
🏗️ Architecture
```text
                    ┌─────────────────┐
                    │   Redis Client  │
                    └────────┬────────┘
                             │
                             │ TCP
                             ▼
                    ┌─────────────────┐
                    │   Redis Server  │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ Command Handler │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │    DataStore    │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
          In-Memory       TTL/Expiry     Persistence
           Storage
```
The project is being evolved toward:
```text
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
🧰 Tech Stack
Java 21
Maven
JUnit 5
Mockito
AssertJ
SLF4J
Logback
TCP/IP sockets
Concurrent Java collections
Java NIO
---
📁 Project Structure
```text
src/
├── main/
│   └── java/
│       └── com/
│           └── miniredis/
│               ├── Main.java
│               ├── client/
│               │   ├── RedisClient.java
│               │   └── ClientMain.java
│               ├── command/
│               │   ├── Command.java
│               │   └── CommandHandler.java
│               ├── server/
│               │   ├── RedisServer.java
│               │   └── ClientHandler.java
│               └── store/
│                   └── DataStore.java
│
└── test/
    └── java/
        └── com/
            └── miniredis/
                └── DataStoreTest.java
```
---
⚙️ Requirements
JDK 21+
Maven 3.9+
Check your installation:
```bash
java -version
javac -version
mvn -version
```
---
🔨 Build
Clone the repository:
```bash
git clone https://github.com/mdebabrata2004/build-your-own-redis.git
cd build-your-own-redis
```
Compile:
```bash
mvn clean compile
```
Run tests:
```bash
mvn test
```
Package the application:
```bash
mvn package
```
---
▶️ Run
Run the packaged application:
```bash
java -jar target/mini-redis-1.0-SNAPSHOT-all.jar
```
Default server configuration:
```text
Host: 0.0.0.0
Port: 6379
```
Example:
```text
╔══════════════════════════════════════╗
║          Mini Redis Server           ║
╚══════════════════════════════════════╝

Bind       : 0.0.0.0:6379
Threads    : 32
Max clients: 1000
```
---
💻 Example Commands
```text
PING

SET name Rahul

GET name

SET session abc123 EX 60

TTL session

INCR counter

INCRBY counter 10

KEYS *

DBSIZE

DEL name
```
Example session:
```text
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
🧪 Test Coverage
The current test suite covers:
Basic CRUD
Missing keys
Invalid keys and values
TTL expiration
Persistence and reload
Corrupt persistence file recovery
`SET NX`
`SET XX`
Atomic counters
Concurrent `INCR`
`APPEND`
`KEYS`
`SCAN`
Maximum-key eviction
Database flush
Database size
Run the test suite:
```bash
mvn test
```
Current test status:
```text
Tests run: 20
Failures: 0
Errors: 0
Skipped: 0

BUILD SUCCESS
```
---
🛣️ Roadmap
The goal is to progressively evolve this project toward a production-style Redis architecture.
Phase 1 — Core Engine
[x] TCP server
[x] Persistent client connection
[x] Basic commands
[x] TTL
[x] Atomic counters
[x] Persistence
[x] Corruption recovery
[x] Concurrent clients
[x] Unit tests
Phase 2 — Redis Protocol
[ ] RESP2 decoder
[ ] RESP2 encoder
[ ] Proper Redis wire protocol
[ ] Bulk strings
[ ] Arrays
[ ] Error responses
[ ] Protocol integration tests
Phase 3 — Data Structures
[ ] Strings
[ ] Hashes
[ ] Lists
[ ] Sets
[ ] Sorted Sets
Phase 4 — Server Features
[ ] Transactions
[ ] Pub/Sub
[ ] Blocking commands
[ ] Configuration system
[ ] Authentication
[ ] ACL
[ ] TLS
Phase 5 — Production Engineering
[ ] AOF persistence
[ ] AOF rewrite
[ ] Recovery strategy
[ ] Metrics
[ ] Structured logging
[ ] Benchmarking
[ ] JFR profiling
[ ] Failure testing
[ ] Load testing
Phase 6 — Distributed Redis
[ ] Replication
[ ] Primary/Replica architecture
[ ] Failover
[ ] Sharding
[ ] Cluster/slot architecture
---
🎯 Why This Project?
This project is primarily a learning and engineering exercise.
Instead of only using Redis as a black box, the goal is to understand the systems underneath it:
How TCP servers work
How concurrent clients are handled
How in-memory storage works
How TTL and expiration are implemented
How atomic operations work
How persistence works
How database protocols are designed
How production systems handle failure and recovery
How distributed systems scale
---
📊 Project Status
Status: 🚧 Active Development
Current focus:
> **RESP2 protocol implementation**
The project is intentionally being built incrementally, with tests and production-oriented design decisions introduced along the way.
---
🤝 Contributing
This is currently a personal learning and engineering project, but ideas, issues, and improvements are welcome.
If you find a bug or have an architectural suggestion, feel free to open an issue.
---
📜 License
This project is currently intended as a personal learning and engineering project.