package com.miniredis.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite-backed relational metadata repository for multi-tenant accounts, projects,
 * secure API keys, and immutable audit logs.
 */
public final class MetadataRepository implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MetadataRepository.class);

    private final String jdbcUrl;
    private final Connection connection;

    public MetadataRepository(Path dbPath) {
        try {
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }
            this.jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
            this.connection = DriverManager.getConnection(jdbcUrl);
            initDatabase();
        } catch (SQLException | IOException e) {
            throw new RuntimeException("Failed to initialize MetadataRepository at " + dbPath, e);
        }
    }

    private synchronized void initDatabase() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA foreign_keys=ON;");

            // Accounts
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id TEXT PRIMARY KEY,
                    email TEXT UNIQUE NOT NULL,
                    name TEXT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    status TEXT NOT NULL DEFAULT 'ACTIVE'
                );
            """);

            stmt.execute("""
                INSERT OR IGNORE INTO accounts(id, email, name, created_at, status)
                VALUES ('acc_default', 'admin@localdata.dev', 'Default Admin', CURRENT_TIMESTAMP, 'ACTIVE');
            """);

            // Projects
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS projects (
                    id TEXT PRIMARY KEY,
                    account_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    slug TEXT UNIQUE NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    rate_limit_rps INTEGER NOT NULL DEFAULT 100,
                    rate_limit_burst INTEGER NOT NULL DEFAULT 200,
                    max_storage_bytes INTEGER NOT NULL DEFAULT 104857600,
                    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
                );
            """);

            // API Keys (never stores plaintext secret!)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS api_keys (
                    id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    key_prefix TEXT NOT NULL,
                    key_hash TEXT UNIQUE NOT NULL,
                    scopes TEXT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    expires_at TIMESTAMP,
                    revoked_at TIMESTAMP,
                    last_used_at TIMESTAMP,
                    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
                );
            """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_api_keys_hash ON api_keys(key_hash);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_api_keys_project ON api_keys(project_id);");

            // Audit Logs
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS audit_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_id TEXT,
                    action TEXT NOT NULL,
                    actor TEXT NOT NULL,
                    details TEXT,
                    ip_address TEXT,
                    timestamp TIMESTAMP NOT NULL
                );
            """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_project ON audit_logs(project_id);");

            // Usage Metrics
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS metrics (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_id TEXT NOT NULL,
                    metric_name TEXT NOT NULL,
                    metric_value REAL NOT NULL,
                    recorded_at TIMESTAMP NOT NULL
                );
            """);
        }
        log.info("MetadataRepository database initialized successfully.");
    }

    // ──────────────────────────────────────────────────────────────
    // Accounts
    // ──────────────────────────────────────────────────────────────

    public synchronized void createAccount(Account account) {
        String sql = "INSERT OR IGNORE INTO accounts(id, email, name, created_at, status) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, account.id());
            ps.setString(2, account.email());
            ps.setString(3, account.name());
            ps.setTimestamp(4, Timestamp.from(account.createdAt()));
            ps.setString(5, account.status());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert account", e);
        }
    }

    public synchronized Optional<Account> getAccount(String id) {
        String sql = "SELECT id, email, name, created_at, status FROM accounts WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Account(
                            rs.getString("id"),
                            rs.getString("email"),
                            rs.getString("name"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getString("status")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get account", e);
        }
        return Optional.empty();
    }

    // ──────────────────────────────────────────────────────────────
    // Projects
    // ──────────────────────────────────────────────────────────────

    public synchronized void createProject(Project project) {
        String sql = """
            INSERT INTO projects(id, account_id, name, slug, created_at, rate_limit_rps, rate_limit_burst, max_storage_bytes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, project.id());
            ps.setString(2, project.accountId());
            ps.setString(3, project.name());
            ps.setString(4, project.slug());
            ps.setTimestamp(5, Timestamp.from(project.createdAt()));
            ps.setInt(6, project.rateLimitRps());
            ps.setInt(7, project.rateLimitBurst());
            ps.setLong(8, project.maxStorageBytes());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create project: " + project.id(), e);
        }
    }

    public synchronized Optional<Project> getProject(String id) {
        String sql = "SELECT id, account_id, name, slug, created_at, rate_limit_rps, rate_limit_burst, max_storage_bytes FROM projects WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Project(
                            rs.getString("id"),
                            rs.getString("account_id"),
                            rs.getString("name"),
                            rs.getString("slug"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getInt("rate_limit_rps"),
                            rs.getInt("rate_limit_burst"),
                            rs.getLong("max_storage_bytes")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get project", e);
        }
        return Optional.empty();
    }

    public synchronized Optional<Project> getProjectBySlug(String slug) {
        String sql = "SELECT id, account_id, name, slug, created_at, rate_limit_rps, rate_limit_burst, max_storage_bytes FROM projects WHERE slug = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Project(
                            rs.getString("id"),
                            rs.getString("account_id"),
                            rs.getString("name"),
                            rs.getString("slug"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getInt("rate_limit_rps"),
                            rs.getInt("rate_limit_burst"),
                            rs.getLong("max_storage_bytes")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query project by slug", e);
        }
        return Optional.empty();
    }

    public synchronized List<Project> listProjects() {
        List<Project> list = new ArrayList<>();
        String sql = "SELECT id, account_id, name, slug, created_at, rate_limit_rps, rate_limit_burst, max_storage_bytes FROM projects ORDER BY created_at ASC";
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new Project(
                        rs.getString("id"),
                        rs.getString("account_id"),
                        rs.getString("name"),
                        rs.getString("slug"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getInt("rate_limit_rps"),
                        rs.getInt("rate_limit_burst"),
                        rs.getLong("max_storage_bytes")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list projects", e);
        }
        return list;
    }

    public synchronized boolean deleteProject(String id) {
        String sql = "DELETE FROM projects WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete project", e);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // API Keys
    // ──────────────────────────────────────────────────────────────

    public synchronized void createApiKey(ApiKey key) {
        String sql = """
            INSERT INTO api_keys(id, project_id, name, key_prefix, key_hash, scopes, created_at, expires_at, revoked_at, last_used_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key.id());
            ps.setString(2, key.projectId());
            ps.setString(3, key.name());
            ps.setString(4, key.keyPrefix());
            ps.setString(5, key.keyHash());
            ps.setString(6, key.scopesRaw());
            ps.setTimestamp(7, Timestamp.from(key.createdAt()));
            ps.setTimestamp(8, key.expiresAt() != null ? Timestamp.from(key.expiresAt()) : null);
            ps.setTimestamp(9, key.revokedAt() != null ? Timestamp.from(key.revokedAt()) : null);
            ps.setTimestamp(10, key.lastUsedAt() != null ? Timestamp.from(key.lastUsedAt()) : null);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to store API key", e);
        }
    }

    public synchronized Optional<ApiKey> findApiKeyByHash(String keyHash) {
        String sql = "SELECT id, project_id, name, key_prefix, key_hash, scopes, created_at, expires_at, revoked_at, last_used_at FROM api_keys WHERE key_hash = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, keyHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapApiKey(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query API key by hash", e);
        }
        return Optional.empty();
    }

    public synchronized List<ApiKey> listApiKeys(String projectId) {
        List<ApiKey> list = new ArrayList<>();
        String sql = "SELECT id, project_id, name, key_prefix, key_hash, scopes, created_at, expires_at, revoked_at, last_used_at FROM api_keys WHERE project_id = ? ORDER BY created_at DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapApiKey(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list API keys for project: " + projectId, e);
        }
        return list;
    }

    public synchronized boolean revokeApiKey(String id, String projectId) {
        String sql = "UPDATE api_keys SET revoked_at = ? WHERE id = ? AND project_id = ? AND revoked_at IS NULL";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, id);
            ps.setString(3, projectId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to revoke API key", e);
        }
    }

    public synchronized void updateApiKeyLastUsed(String id) {
        String sql = "UPDATE api_keys SET last_used_at = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to update last_used_at for key: {}", id, e);
        }
    }

    private ApiKey mapApiKey(ResultSet rs) throws SQLException {
        Timestamp exp = rs.getTimestamp("expires_at");
        Timestamp rev = rs.getTimestamp("revoked_at");
        Timestamp lu = rs.getTimestamp("last_used_at");
        return new ApiKey(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("name"),
                rs.getString("key_prefix"),
                rs.getString("key_hash"),
                rs.getString("scopes"),
                rs.getTimestamp("created_at").toInstant(),
                exp != null ? exp.toInstant() : null,
                rev != null ? rev.toInstant() : null,
                lu != null ? lu.toInstant() : null
        );
    }

    // ──────────────────────────────────────────────────────────────
    // Audit Logs
    // ──────────────────────────────────────────────────────────────

    public synchronized void recordAuditLog(AuditLogEntry entry) {
        String sql = "INSERT INTO audit_logs(project_id, action, actor, details, ip_address, timestamp) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, entry.projectId());
            ps.setString(2, entry.action());
            ps.setString(3, entry.actor());
            ps.setString(4, entry.details());
            ps.setString(5, entry.ipAddress());
            ps.setTimestamp(6, Timestamp.from(entry.timestamp()));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to record audit log: {}", entry.action(), e);
        }
    }

    public synchronized List<AuditLogEntry> listAuditLogs(String projectId, int limit) {
        List<AuditLogEntry> list = new ArrayList<>();
        String sql = projectId != null && !projectId.isBlank()
                ? "SELECT id, project_id, action, actor, details, ip_address, timestamp FROM audit_logs WHERE project_id = ? ORDER BY timestamp DESC LIMIT ?"
                : "SELECT id, project_id, action, actor, details, ip_address, timestamp FROM audit_logs ORDER BY timestamp DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (projectId != null && !projectId.isBlank()) {
                ps.setString(1, projectId);
                ps.setInt(2, limit);
            } else {
                ps.setInt(1, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new AuditLogEntry(
                            rs.getLong("id"),
                            rs.getString("project_id"),
                            rs.getString("action"),
                            rs.getString("actor"),
                            rs.getString("details"),
                            rs.getString("ip_address"),
                            rs.getTimestamp("timestamp").toInstant()
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query audit logs", e);
        }
        return list;
    }

    @Override
    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            log.error("Error closing SQLite connection", e);
        }
    }
}
