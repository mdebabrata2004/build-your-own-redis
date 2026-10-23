package com.miniredis.storage;

import com.miniredis.store.DataStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Concrete StorageEngine backed by the production-hardened in-memory DataStore.
 * Provides active/lazy TTL expiration, CRC32 RDB persistence snapshots, and atomic math.
 */
public final class EmbeddedStorageEngine implements StorageEngine {

    private final DataStore store;

    public EmbeddedStorageEngine() {
        this(new DataStore());
    }

    public EmbeddedStorageEngine(Path dataFile) {
        this(new DataStore(new DataStore.Config().dataFile(dataFile)));
    }

    public EmbeddedStorageEngine(DataStore store) {
        this.store = Objects.requireNonNull(store, "DataStore cannot be null");
    }

    public DataStore getRawStore() {
        return store;
    }

    @Override
    public String get(String key) {
        return store.get(key);
    }

    @Override
    public boolean set(String key, String value, SetOptions options) {
        if (options == null) {
            options = SetOptions.defaults();
        }

        long ttlSeconds = options.exSeconds();
        if (ttlSeconds <= 0 && options.pxMillis() > 0) {
            ttlSeconds = Math.max(1, (options.pxMillis() + 999) / 1000);
        }

        if (options.isNx()) {
            return store.set(key, value, true, false, ttlSeconds);
        }
        if (options.isXx()) {
            return store.set(key, value, false, true, ttlSeconds);
        }

        store.set(key, value, ttlSeconds);
        return true;
    }

    @Override
    public boolean delete(String key) {
        return store.delete(key);
    }

    @Override
    public long deleteMulti(List<String> keys) {
        if (keys == null || keys.isEmpty()) return 0;
        int deleted = 0;
        for (String k : keys) {
            if (store.delete(k)) deleted++;
        }
        return deleted;
    }

    @Override
    public boolean exists(String key) {
        return store.exists(key);
    }

    @Override
    public boolean expire(String key, long seconds) {
        return store.expire(key, seconds);
    }

    @Override
    public boolean pexpire(String key, long millis) {
        long seconds = Math.max(1, (millis + 999) / 1000);
        return store.expire(key, seconds);
    }

    @Override
    public long ttl(String key) {
        return store.ttl(key);
    }

    @Override
    public long pttl(String key) {
        return store.pttl(key);
    }

    @Override
    public boolean persist(String key) {
        return store.persist(key);
    }

    @Override
    public long incrBy(String key, long delta) {
        return store.incrBy(key, delta);
    }

    @Override
    public long append(String key, String value) {
        return store.append(key, value);
    }

    @Override
    public long strLen(String key) {
        String val = store.get(key);
        return val != null ? val.length() : 0;
    }

    @Override
    public List<String> mget(List<String> keys) {
        if (keys == null || keys.isEmpty()) return List.of();
        List<String> list = new ArrayList<>(keys.size());
        for (String k : keys) {
            list.add(store.get(k));
        }
        return list;
    }

    @Override
    public void mset(Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) return;
        for (Map.Entry<String, String> e : entries.entrySet()) {
            store.set(e.getKey(), e.getValue());
        }
    }

    @Override
    public List<String> keys(String pattern) {
        return new ArrayList<>(store.keys(pattern));
    }

    @Override
    public ScanResult scan(String cursor, String pattern, int count) {
        long c = 0;
        if (cursor != null && !cursor.isBlank()) {
            try {
                c = Long.parseLong(cursor.trim());
            } catch (NumberFormatException ignored) {}
        }

        DataStore.ScanResult raw = store.scan(c, pattern, count);
        return new ScanResult(String.valueOf(raw.nextCursor), raw.keys);
    }

    @Override
    public long dbSize() {
        return store.size();
    }

    @Override
    public void flushDb() {
        store.flush();
    }

    @Override
    public void save() {
        store.save();
    }

    @Override
    public Map<String, Object> info() {
        return store.info();
    }

    @Override
    public void close() {
        store.close();
    }
}
