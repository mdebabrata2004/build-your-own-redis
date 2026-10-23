package com.miniredis.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Decorator that enforces strict physical multi-tenant isolation.
 * Automatically prefixes all keys with `p:{projectId}:d:{databaseId}:`.
 * Cross-tenant access, scanning, or flushing is completely impossible.
 */
public final class TenantStorageEngine implements StorageEngine {

    private final StorageEngine rootEngine;
    private final String projectId;
    private final int databaseId;
    private final String prefix;

    public TenantStorageEngine(StorageEngine rootEngine, String projectId, int databaseId) {
        this.rootEngine = Objects.requireNonNull(rootEngine, "rootEngine cannot be null");
        this.projectId = Objects.requireNonNull(projectId, "projectId cannot be null");
        this.databaseId = databaseId;
        this.prefix = "p:" + projectId + ":d:" + databaseId + ":";
    }

    public String getProjectId() {
        return projectId;
    }

    public int getDatabaseId() {
        return databaseId;
    }

    private String toInternal(String userKey) {
        if (userKey == null || userKey.isBlank()) {
            throw new IllegalArgumentException("Key cannot be null or empty");
        }
        return prefix + userKey;
    }

    private String fromInternal(String internalKey) {
        if (internalKey != null && internalKey.startsWith(prefix)) {
            return internalKey.substring(prefix.length());
        }
        return internalKey;
    }

    @Override
    public String get(String key) {
        return rootEngine.get(toInternal(key));
    }

    @Override
    public boolean set(String key, String value, SetOptions options) {
        return rootEngine.set(toInternal(key), value, options);
    }

    @Override
    public boolean delete(String key) {
        return rootEngine.delete(toInternal(key));
    }

    @Override
    public long deleteMulti(List<String> keys) {
        if (keys == null || keys.isEmpty()) return 0;
        List<String> internal = keys.stream()
                .filter(Objects::nonNull)
                .map(this::toInternal)
                .collect(Collectors.toList());
        return rootEngine.deleteMulti(internal);
    }

    @Override
    public boolean exists(String key) {
        return rootEngine.exists(toInternal(key));
    }

    @Override
    public boolean expire(String key, long seconds) {
        return rootEngine.expire(toInternal(key), seconds);
    }

    @Override
    public boolean pexpire(String key, long millis) {
        return rootEngine.pexpire(toInternal(key), millis);
    }

    @Override
    public long ttl(String key) {
        return rootEngine.ttl(toInternal(key));
    }

    @Override
    public long pttl(String key) {
        return rootEngine.pttl(toInternal(key));
    }

    @Override
    public boolean persist(String key) {
        return rootEngine.persist(toInternal(key));
    }

    @Override
    public long incrBy(String key, long delta) {
        return rootEngine.incrBy(toInternal(key), delta);
    }

    @Override
    public long append(String key, String value) {
        return rootEngine.append(toInternal(key), value);
    }

    @Override
    public long strLen(String key) {
        return rootEngine.strLen(toInternal(key));
    }

    @Override
    public List<String> mget(List<String> keys) {
        if (keys == null || keys.isEmpty()) return List.of();
        List<String> internalKeys = keys.stream()
                .map(this::toInternal)
                .collect(Collectors.toList());
        return rootEngine.mget(internalKeys);
    }

    @Override
    public void mset(Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) return;
        Map<String, String> internal = new HashMap<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            internal.put(toInternal(e.getKey()), e.getValue());
        }
        rootEngine.mset(internal);
    }

    @Override
    public List<String> keys(String pattern) {
        String pat = (pattern == null || pattern.isBlank() || pattern.equals("*"))
                ? prefix + "*"
                : prefix + pattern;

        List<String> internal = rootEngine.keys(pat);
        List<String> result = new ArrayList<>(internal.size());
        for (String k : internal) {
            if (k.startsWith(prefix)) {
                result.add(fromInternal(k));
            }
        }
        return result;
    }

    @Override
    public ScanResult scan(String cursor, String pattern, int count) {
        String pat = (pattern == null || pattern.isBlank() || pattern.equals("*"))
                ? prefix + "*"
                : prefix + pattern;

        ScanResult raw = rootEngine.scan(cursor, pat, count);
        List<String> stripped = new ArrayList<>(raw.keys().size());
        for (String k : raw.keys()) {
            if (k.startsWith(prefix)) {
                stripped.add(fromInternal(k));
            }
        }
        return new ScanResult(raw.nextCursor(), stripped);
    }

    @Override
    public long dbSize() {
        return keys("*").size();
    }

    @Override
    public void flushDb() {
        List<String> projectKeys = rootEngine.keys(prefix + "*");
        if (!projectKeys.isEmpty()) {
            rootEngine.deleteMulti(projectKeys);
        }
    }

    @Override
    public void save() {
        rootEngine.save();
    }

    @Override
    public void close() {
        // Root engine lifecycle managed externally
    }
}
