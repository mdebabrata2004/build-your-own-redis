package com.miniredis.storage;

import java.util.List;
import java.util.Map;

public interface StorageEngine extends AutoCloseable {

    String get(String key);

    boolean set(String key, String value, SetOptions options);

    default boolean set(String key, String value) {
        return set(key, value, SetOptions.defaults());
    }

    default boolean set(String key, String value, boolean nx, boolean xx, long ttlSeconds) {
        return set(key, value, new SetOptions(ttlSeconds, 0, nx, xx, false));
    }

    boolean delete(String key);

    long deleteMulti(List<String> keys);

    boolean exists(String key);

    boolean expire(String key, long seconds);

    boolean pexpire(String key, long millis);

    long ttl(String key);

    long pttl(String key);

    boolean persist(String key);

    long incrBy(String key, long delta);

    default long incr(String key) {
        return incrBy(key, 1);
    }

    default long decr(String key) {
        return incrBy(key, -1);
    }

    default long decrBy(String key, long delta) {
        return incrBy(key, -delta);
    }

    long append(String key, String value);

    long strLen(String key);

    List<String> mget(List<String> keys);

    void mset(Map<String, String> entries);

    List<String> keys(String pattern);

    ScanResult scan(String cursor, String pattern, int count);

    default ScanResult scan(long cursor, String pattern, int count) {
        return scan(String.valueOf(cursor), pattern, count);
    }

    long dbSize();

    default long size() {
        return dbSize();
    }

    void flushDb();

    default void flush() {
        flushDb();
    }

    void save();

    default Map<String, Object> info() {
        return Map.of("keys", dbSize());
    }

    @Override
    void close();
}
