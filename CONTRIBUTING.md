# Contributing to LocalData

Thank you for your interest in contributing to **LocalData**! We welcome community contributions, bug fixes, and feature enhancements.

---

## 🛠️ Development Setup

### Prerequisites
- **JDK 21** or newer (with Virtual Threads enabled)
- **Apache Maven 3.9+**
- **Node.js 18+** & npm (for the TypeScript SDK)
- **Git**

### Building the Project
```bash
git clone https://github.com/mdebabrata2004/build-your-own-redis.git
cd build-your-own-redis

# Run test suite
mvn test

# Package JAR
mvn clean package -DskipTests
```

### Running Locally
```bash
java -jar target/mini-redis-1.0-SNAPSHOT-all.jar
```
Visit `http://localhost:8080/dashboard` to verify local operation.

---

## 🧪 Testing Guidelines

Before opening a pull request, ensure:
1. `mvn test` passes completely with zero failures.
2. If adding new REST or RESP2 commands, include unit tests under `src/test/java/com/miniredis/`.
3. If modifying the TypeScript client, run `npm test` inside `sdk/typescript/`.

---

## 🔀 Pull Request Process

1. Fork the repository and create your branch from `main`:
   ```bash
   git checkout -b feature/my-new-feature
   ```
2. Commit your changes with clear, semantic commit messages:
   ```bash
   git commit -m "feat(storage): add sliding window expiration support"
   ```
3. Push to your fork and submit a Pull Request to `main`.
4. Ensure all CI checks pass. A maintainer will review your contribution.

---

## 🔒 Security Vulnerabilities

If you discover a security vulnerability, please refer to [docs/SECURITY.md](docs/SECURITY.md) to report it privately rather than creating a public GitHub issue.
