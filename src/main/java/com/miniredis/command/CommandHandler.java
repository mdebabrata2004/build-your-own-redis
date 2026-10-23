package com.miniredis.command;

import com.miniredis.security.ApiKeyService;
import com.miniredis.security.SecurityContext;
import com.miniredis.storage.EmbeddedStorageEngine;
import com.miniredis.storage.ScanResult;
import com.miniredis.storage.StorageEngine;
import com.miniredis.store.DataStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Translates Redis commands into StorageEngine operations and returns RESP replies.
 * Supports multi-tenant AUTH, String, Key, Counter, and Server commands.
 */
public final class CommandHandler {

    private final StorageEngine store;
    private final ApiKeyService apiKeyService;

    public CommandHandler(StorageEngine store) {
        this(store, null);
    }

    public CommandHandler(StorageEngine store, ApiKeyService apiKeyService) {
        this.store = Objects.requireNonNull(store, "StorageEngine cannot be null");
        this.apiKeyService = apiKeyService;
    }

    public CommandHandler(DataStore store) {
        this(new EmbeddedStorageEngine(store), null);
    }

    public StorageEngine getStore() {
        return store;
    }

    /**
     * @param args RESP array (args[0] = command name)
     * @return full RESP response including \\r\\n
     */
    public String handle(List<String> args) {
        if (args == null || args.isEmpty()) {
            return error("empty command");
        }

        String cmd = args.get(0).toUpperCase(Locale.ROOT);

        try {
            return switch (cmd) {

                // Connection & Auth
                case "PING"     -> handlePing(args);
                case "QUIT"     -> handleQuit();
                case "ECHO"     -> handleEcho(args);
                case "AUTH"     -> handleAuth(args);

                // String
                case "SET"      -> handleSet(args);
                case "GET"      -> handleGet(args);
                case "MGET"     -> handleMGet(args);
                case "MSET"     -> handleMSet(args);
                case "GETSET"   -> handleGetSet(args);
                case "APPEND"   -> handleAppend(args);
                case "STRLEN"   -> handleStrLen(args);

                // Key
                case "DEL", "DELETE" -> handleDel(args);
                case "EXISTS"   -> handleExists(args);
                case "EXPIRE"   -> handleExpire(args);
                case "PERSIST"  -> handlePersist(args);
                case "TTL"      -> handleTtl(args);
                case "PTTL"     -> handlePttl(args);
                case "TYPE"     -> handleType(args);
                case "KEYS"     -> handleKeys(args);
                case "SCAN"     -> handleScan(args);
                case "FLUSHDB", "FLUSHALL" -> handleFlush();
                case "DBSIZE"   -> handleDbSize();

                // Counters
                case "INCR"     -> handleIncr(args);
                case "DECR"     -> handleDecr(args);
                case "INCRBY"   -> handleIncrBy(args);
                case "DECRBY"   -> handleDecrBy(args);

                // Server
                case "INFO"     -> handleInfo(args);
                case "COMMAND"  -> handleCommand(args);
                case "SAVE"     -> handleSave();
                case "BGSAVE"   -> handleBgSave();

                default -> error("unknown command '" + args.get(0) + "'");
            };
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            return error("internal error: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Connection & Auth
    // ──────────────────────────────────────────────────────────────

    private String handlePing(List<String> args) {
        if (args.size() == 1) return simple("PONG");
        if (args.size() == 2) return bulk(args.get(1));
        return error("wrong number of arguments for 'ping' command");
    }

    private String handleQuit() {
        return simple("OK");
    }

    private String handleEcho(List<String> args) {
        if (args.size() != 2) {
            return error("wrong number of arguments for 'echo' command");
        }
        return bulk(args.get(1));
    }

    private String handleAuth(List<String> args) {
        if (args.size() < 2 || args.size() > 3) {
            return error("wrong number of arguments for 'auth' command");
        }
        String password = args.size() == 2 ? args.get(1) : args.get(2);
        if (apiKeyService == null) {
            return simple("OK");
        }
        Optional<SecurityContext> ctx = apiKeyService.authenticate(password);
        if (ctx.isPresent()) {
            return simple("OK");
        } else {
            return error("WRONGPASS invalid username-password pair or user is disabled.");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // String
    // ──────────────────────────────────────────────────────────────

    private String handleSet(List<String> args) {
        if (args.size() < 3) {
            return error("wrong number of arguments for 'set' command");
        }

        String key = args.get(1);
        String value = args.get(2);
        boolean nx = false;
        boolean xx = false;
        long ttlSeconds = 0;

        for (int i = 3; i < args.size(); i++) {
            String opt = args.get(i).toUpperCase(Locale.ROOT);
            switch (opt) {
                case "NX" -> nx = true;
                case "XX" -> xx = true;
                case "EX" -> {
                    if (i + 1 >= args.size()) return error("syntax error");
                    ttlSeconds = parsePositiveLong(args.get(++i));
                }
                case "PX" -> {
                    if (i + 1 >= args.size()) return error("syntax error");
                    long ms = parsePositiveLong(args.get(++i));
                    ttlSeconds = Math.max(1, (ms + 999) / 1000);
                }
                default -> {
                    return error("syntax error");
                }
            }
        }

        if (nx && xx) return error("syntax error");

        boolean set = store.set(key, value, nx, xx, ttlSeconds);
        return set ? simple("OK") : nullBulk();
    }

    private String handleGet(List<String> args) {
        if (args.size() != 2) {
            return error("wrong number of arguments for 'get' command");
        }
        return bulk(store.get(args.get(1)));
    }

    private String handleMGet(List<String> args) {
        if (args.size() < 2) {
            return error("wrong number of arguments for 'mget' command");
        }
        List<String> keys = args.subList(1, args.size());
        List<String> values = store.mget(keys);
        return arrayOfBulksAllowNull(values);
    }

    private String handleMSet(List<String> args) {
        if (args.size() < 3 || (args.size() - 1) % 2 != 0) {
            return error("wrong number of arguments for 'mset' command");
        }
        Map<String, String> entries = new HashMap<>();
        for (int i = 1; i < args.size(); i += 2) {
            entries.put(args.get(i), args.get(i + 1));
        }
        store.mset(entries);
        return simple("OK");
    }

    private String handleGetSet(List<String> args) {
        if (args.size() != 3) {
            return error("wrong number of arguments for 'getset' command");
        }
        String key = args.get(1);
        String newVal = args.get(2);
        String oldVal = store.get(key);
        store.set(key, newVal);
        return bulk(oldVal);
    }

    private String handleAppend(List<String> args) {
        if (args.size() != 3) {
            return error("wrong number of arguments for 'append' command");
        }
        long newLen = store.append(args.get(1), args.get(2));
        return integer(newLen);
    }

    private String handleStrLen(List<String> args) {
        if (args.size() != 2) {
            return error("wrong number of arguments for 'strlen' command");
        }
        return integer(store.strLen(args.get(1)));
    }

    // ──────────────────────────────────────────────────────────────
    // Key
    // ──────────────────────────────────────────────────────────────

    private String handleDel(List<String> args) {
        if (args.size() < 2) {
            return error("wrong number of arguments for 'del' command");
        }
        List<String> keys = args.subList(1, args.size());
        return integer(store.deleteMulti(keys));
    }

    private String handleExists(List<String> args) {
        if (args.size() < 2) {
            return error("wrong number of arguments for 'exists' command");
        }
        int count = 0;
        for (int i = 1; i < args.size(); i++) {
            if (store.exists(args.get(i))) count++;
        }
        return integer(count);
    }

    private String handleExpire(List<String> args) {
        if (args.size() != 3) {
            return error("wrong number of arguments for 'expire' command");
        }
        long seconds = parseLong(args.get(2));
        if (seconds < 0) {
            return error("invalid expire time");
        }
        return integer(store.expire(args.get(1), seconds) ? 1 : 0);
    }

    private String handlePersist(List<String> args) {
        if (args.size() != 2) {
            return error("wrong number of arguments for 'persist' command");
        }
        return integer(store.persist(args.get(1)) ? 1 : 0);
    }

    private String handleTtl(List<String> args) {
        if (args.size() != 2) {
            return error("wrong number of arguments for 'ttl' command");
        }
        return integer(store.ttl(args.get(1)));
    }

    private String handlePttl(List<String> args) {
        if (args.size() != 2) {
            return error("wrong number of arguments for 'pttl' command");
        }
        return integer(store.pttl(args.get(1)));
    }

    private String handleType(List<String> args) {
        if (args.size() != 2) {
            return error("wrong number of arguments for 'type' command");
        }
        if (!store.exists(args.get(1))) {
            return simple("none");
        }
        return simple("string");
    }

    private String handleKeys(List<String> args) {
        if (args.size() != 2) {
            return error("wrong number of arguments for 'keys' command");
        }
        List<String> keys = store.keys(args.get(1));
        return arrayOfBulks(keys);
    }

    private String handleScan(List<String> args) {
        if (args.size() < 2) {
            return error("wrong number of arguments for 'scan' command");
        }

        String cursor = args.get(1);
        String pattern = "*";
        int count = 10;

        for (int i = 2; i < args.size(); i++) {
            String opt = args.get(i).toUpperCase(Locale.ROOT);
            if (opt.equals("MATCH")) {
                if (i + 1 >= args.size()) return error("syntax error");
                pattern = args.get(++i);
            } else if (opt.equals("COUNT")) {
                if (i + 1 >= args.size()) return error("syntax error");
                count = (int) parsePositiveLong(args.get(++i));
            } else {
                return error("syntax error");
            }
        }

        ScanResult result = store.scan(cursor, pattern, count);

        StringBuilder sb = new StringBuilder();
        sb.append("*2\r\n");
        sb.append(bulk(result.nextCursor()));
        sb.append(arrayOfBulks(result.keys()));
        return sb.toString();
    }

    private String handleFlush() {
        store.flushDb();
        return simple("OK");
    }

    private String handleDbSize() {
        return integer(store.dbSize());
    }

    // ──────────────────────────────────────────────────────────────
    // Counters
    // ──────────────────────────────────────────────────────────────

    private String handleIncr(List<String> args) {
        if (args.size() != 2) {
            return error("wrong number of arguments for 'incr' command");
        }
        return integer(store.incr(args.get(1)));
    }

    private String handleDecr(List<String> args) {
        if (args.size() != 2) {
            return error("wrong number of arguments for 'decr' command");
        }
        return integer(store.decr(args.get(1)));
    }

    private String handleIncrBy(List<String> args) {
        if (args.size() != 3) {
            return error("wrong number of arguments for 'incrby' command");
        }
        return integer(store.incrBy(args.get(1), parseLong(args.get(2))));
    }

    private String handleDecrBy(List<String> args) {
        if (args.size() != 3) {
            return error("wrong number of arguments for 'decrby' command");
        }
        return integer(store.incrBy(args.get(1), -parseLong(args.get(2))));
    }

    // ──────────────────────────────────────────────────────────────
    // Server
    // ──────────────────────────────────────────────────────────────

    private String handleInfo(List<String> args) {
        Map<String, Object> info = store.info();
        StringBuilder sb = new StringBuilder(256);
        sb.append("# Server\r\n");
        sb.append("redis_version:localdata-redis-1.0\r\n");
        sb.append("platform:LocalData Managed Redis\r\n");
        sb.append("\r\n# Keyspace\r\n");
        sb.append("db0:keys=").append(info.getOrDefault("keys", store.dbSize())).append("\r\n");
        sb.append("\r\n# Stats\r\n");
        info.forEach((k, v) -> sb.append(k).append(':').append(v).append("\r\n"));
        return bulk(sb.toString());
    }

    private String handleCommand(List<String> args) {
        return arrayOfBulks(List.of(
                "ping", "quit", "echo", "auth",
                "set", "get", "mget", "mset", "getset", "append", "strlen",
                "del", "exists", "expire", "persist", "ttl", "pttl", "type",
                "keys", "scan", "flushdb", "dbsize",
                "incr", "decr", "incrby", "decrby",
                "info", "command", "save", "bgsave"
        ));
    }

    private String handleSave() {
        store.save();
        return simple("OK");
    }

    private String handleBgSave() {
        store.save();
        return simple("Background saving started");
    }

    // ──────────────────────────────────────────────────────────────
    // RESP helpers
    // ──────────────────────────────────────────────────────────────

    private static String simple(String msg) {
        return "+" + msg + "\r\n";
    }

    private static String error(String msg) {
        return "-ERR " + msg + "\r\n";
    }

    private static String integer(long value) {
        return ":" + value + "\r\n";
    }

    private static String bulk(String value) {
        if (value == null) return nullBulk();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return "$" + bytes.length + "\r\n" + value + "\r\n";
    }

    private static String nullBulk() {
        return "$-1\r\n";
    }

    private static String arrayOfBulks(Iterable<String> items) {
        StringBuilder body = new StringBuilder();
        int count = 0;
        for (String item : items) {
            body.append(bulk(item));
            count++;
        }
        return "*" + count + "\r\n" + body;
    }

    private static String arrayOfBulksAllowNull(List<String> items) {
        StringBuilder body = new StringBuilder();
        for (String item : items) {
            body.append(item == null ? nullBulk() : bulk(item));
        }
        return "*" + items.size() + "\r\n" + body;
    }

    private static long parseLong(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("value is not an integer or out of range");
        }
    }

    private static long parsePositiveLong(String raw) {
        long v = parseLong(raw);
        if (v <= 0) {
            throw new IllegalArgumentException("value must be positive");
        }
        return v;
    }
}
