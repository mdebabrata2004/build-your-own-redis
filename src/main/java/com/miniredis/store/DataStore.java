package com.miniredis.store;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.CRC32;

/**
 * Hardened in-memory key-value store (string type) with Redis-like semantics.
 *
 * Improvements over previous version:
 *  - RDB CRC32 checksum
 *  - Safe load recovery (corrupt/old file → rename + empty start)
 *  - Approximate LRU via last-access time
 *  - Cursor-based SCAN
 *  - Single-pass snapshot save
 *  - Cleaner metrics + dirty tracking
 *  - Stronger validation and lifecycle
 */
public final class DataStore implements AutoCloseable {

    private static final int MAGIC = 0x4D524453; // "MRDS"
    private static final int VERSION = 2;        // bumped: now includes checksum + access time

    // ──────────────────────────────────────────────────────────────
    // Config
    // ──────────────────────────────────────────────────────────────

    public static final class Config {
        private Path dataFile = Path.of("redis.data");
        private long saveIntervalSeconds = 5;
        private int maxKeys = 0;                 // 0 = unlimited
        private boolean activeExpiration = true;
        private int expirationSampleSize = 20;
        private boolean saveOnClose = true;

        public Config dataFile(Path path) {
            this.dataFile = Objects.requireNonNull(path);
            return this;
        }

        public Config saveIntervalSeconds(long seconds) {
            if (seconds < 1) throw new IllegalArgumentException("saveInterval must be >= 1");
            this.saveIntervalSeconds = seconds;
            return this;
        }

        public Config maxKeys(int max) {
            if (max < 0) throw new IllegalArgumentException("maxKeys must be >= 0");
            this.maxKeys = max;
            return this;
        }

        public Config activeExpiration(boolean enabled) {
            this.activeExpiration = enabled;
            return this;
        }

        public Config expirationSampleSize(int size) {
            if (size < 1) throw new IllegalArgumentException("sample size must be >= 1");
            this.expirationSampleSize = size;
            return this;
        }

        public Config saveOnClose(boolean save) {
            this.saveOnClose = save;
            return this;
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Entry
    // ──────────────────────────────────────────────────────────────

    private static final class Entry {
        volatile String value;
        volatile long expireAt;     // 0 = never
        volatile long lastAccess;   // for approx LRU

        Entry(String value, long expireAt, long lastAccess) {
            this.value = value;
            this.expireAt = expireAt;
            this.lastAccess = lastAccess;
        }

        boolean isExpired(long now) {
            return expireAt > 0 && now >= expireAt;
        }
    }

    // ──────────────────────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────────────────────

    private final ConcurrentHashMap<String, Entry> data = new ConcurrentHashMap<>();
    private final Config config;
    private final Path dataFile;
    private final Path tempFile;
    private final Path corruptBackupFile;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "miniredis-bg");
                t.setDaemon(true);
                return t;
            });

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    // Metrics
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder expiredKeys = new LongAdder();
    private final LongAdder evictedKeys = new LongAdder();
    private final AtomicLong lastSaveTime = new AtomicLong(0);
    private final AtomicLong lastSaveDurationMs = new AtomicLong(0);
    private final AtomicLong lastSaveKeys = new AtomicLong(0);
    private final AtomicLong loadRecoveries = new AtomicLong(0);

    // ──────────────────────────────────────────────────────────────
    // Construction
    // ──────────────────────────────────────────────────────────────

    public DataStore() {
        this(new Config());
    }

    public DataStore(Config config) {
        this.config = Objects.requireNonNull(config);
        this.dataFile = config.dataFile;
        this.tempFile = Path.of(dataFile.toString() + ".tmp");
        this.corruptBackupFile = Path.of(dataFile.toString() + ".corrupt");

        loadSafely();

        scheduler.scheduleAtFixedRate(
                this::safeSave,
                config.saveIntervalSeconds,
                config.saveIntervalSeconds,
                TimeUnit.SECONDS
        );

        if (config.activeExpiration) {
            scheduler.scheduleAtFixedRate(
                    this::activeExpire,
                    100,
                    100,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Core API
    // ──────────────────────────────────────────────────────────────

    public void set(String key, String value) {
        set(key, value, 0);
    }

    public void set(String key, String value, long ttlSeconds) {
        validateKey(key);
        validateValue(value);
        ensureCapacity();

        long now = System.currentTimeMillis();
        long expireAt = ttlSeconds > 0 ? now + ttlSeconds * 1000L : 0L;
        data.put(key, new Entry(value, expireAt, now));
        dirty.set(true);
    }

    /**
     * SET key value [NX|XX] [EX seconds]
     * @return true if set
     */
    public boolean set(String key, String value, boolean nx, boolean xx, long ttlSeconds) {
        validateKey(key);
        validateValue(value);
        if (nx && xx) {
            throw new IllegalArgumentException("NX and XX are mutually exclusive");
        }

        long now = System.currentTimeMillis();
        long expireAt = ttlSeconds > 0 ? now + ttlSeconds * 1000L : 0L;

        if (nx) {
            Entry existing = data.get(key);
            if (existing != null && !existing.isExpired(now)) {
                return false;
            }
            ensureCapacity();
            data.put(key, new Entry(value, expireAt, now));
            dirty.set(true);
            return true;
        }

        if (xx) {
            Entry existing = data.get(key);
            if (existing == null || existing.isExpired(now)) {
                return false;
            }
            existing.value = value;
            existing.expireAt = expireAt;
            existing.lastAccess = now;
            dirty.set(true);
            return true;
        }

        ensureCapacity();
        data.put(key, new Entry(value, expireAt, now));
        dirty.set(true);
        return true;
    }

    public boolean setnx(String key, String value) {
        return set(key, value, true, false, 0);
    }

    public void setex(String key, long seconds, String value) {
        set(key, value, seconds);
    }

    public String get(String key) {
        validateKey(key);
        Entry e = data.get(key);
        if (e == null) {
            misses.increment();
            return null;
        }
        long now = System.currentTimeMillis();
        if (e.isExpired(now)) {
            if (data.remove(key, e)) {
                expiredKeys.increment();
                dirty.set(true);
            }
            misses.increment();
            return null;
        }
        e.lastAccess = now;
        hits.increment();
        return e.value;
    }

    public String getSet(String key, String value) {
        validateKey(key);
        validateValue(value);

        long now = System.currentTimeMillis();
        Entry old = data.get(key);
        String previous = null;
        if (old != null && !old.isExpired(now)) {
            previous = old.value;
        }
        data.put(key, new Entry(value, 0, now));
        dirty.set(true);
        return previous;
    }

    public boolean delete(String key) {
        validateKey(key);
        boolean removed = data.remove(key) != null;
        if (removed) {
            dirty.set(true);
        }
        return removed;
    }

    /** Delete multiple keys; returns number deleted. */
    public int delete(String... keys) {
        int n = 0;
        for (String key : keys) {
            if (delete(key)) n++;
        }
        return n;
    }

    public boolean exists(String key) {
        validateKey(key);
        Entry e = data.get(key);
        if (e == null) return false;
        long now = System.currentTimeMillis();
        if (e.isExpired(now)) {
            if (data.remove(key, e)) {
                expiredKeys.increment();
                dirty.set(true);
            }
            return false;
        }
        return true;
    }

    public boolean expire(String key, long seconds) {
        validateKey(key);
        if (seconds < 0) throw new IllegalArgumentException("seconds must be >= 0");

        Entry e = data.get(key);
        if (e == null) return false;

        long now = System.currentTimeMillis();
        if (e.isExpired(now)) {
            if (data.remove(key, e)) {
                expiredKeys.increment();
                dirty.set(true);
            }
            return false;
        }

        e.expireAt = seconds == 0 ? 0 : now + seconds * 1000L;
        dirty.set(true);
        return true;
    }

    public boolean persist(String key) {
        validateKey(key);
        Entry e = data.get(key);
        if (e == null) return false;
        long now = System.currentTimeMillis();
        if (e.isExpired(now)) {
            if (data.remove(key, e)) {
                expiredKeys.increment();
                dirty.set(true);
            }
            return false;
        }
        if (e.expireAt == 0) return false;
        e.expireAt = 0;
        dirty.set(true);
        return true;
    }

    public long ttl(String key) {
        validateKey(key);
        Entry e = data.get(key);
        if (e == null) return -2;

        long now = System.currentTimeMillis();
        if (e.isExpired(now)) {
            if (data.remove(key, e)) {
                expiredKeys.increment();
                dirty.set(true);
            }
            return -2;
        }
        if (e.expireAt == 0) return -1;
        return Math.max(0, (e.expireAt - now) / 1000L);
    }

    public long pttl(String key) {
        validateKey(key);
        Entry e = data.get(key);
        if (e == null) return -2;

        long now = System.currentTimeMillis();
        if (e.isExpired(now)) {
            if (data.remove(key, e)) {
                expiredKeys.increment();
                dirty.set(true);
            }
            return -2;
        }
        if (e.expireAt == 0) return -1;
        return Math.max(0, e.expireAt - now);
    }

    public long incr(String key) {
        return incrBy(key, 1);
    }

    public long decr(String key) {
        return incrBy(key, -1);
    }

    public long incrBy(String key, long delta) {
        validateKey(key);

        Entry result = data.compute(key, (k, existing) -> {
            long now = System.currentTimeMillis();
            if (existing != null && existing.isExpired(now)) {
                existing = null;
            }

            long current = 0;
            long expireAt = 0;
            if (existing != null) {
                try {
                    current = Long.parseLong(existing.value);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("value is not an integer");
                }
                expireAt = existing.expireAt;
            } else {
                ensureCapacity();
            }

            long next = current + delta;
            // overflow: Redis wraps; we keep Java long behavior (document it)
            return new Entry(Long.toString(next), expireAt, now);
        });

        dirty.set(true);
        return Long.parseLong(result.value);
    }

    public int append(String key, String value) {
        validateKey(key);
        validateValue(value);

        Entry result = data.compute(key, (k, existing) -> {
            long now = System.currentTimeMillis();
            if (existing != null && existing.isExpired(now)) {
                existing = null;
            }
            if (existing == null) {
                ensureCapacity();
                return new Entry(value, 0, now);
            }
            existing.value = existing.value + value;
            existing.lastAccess = now;
            return existing;
        });

        dirty.set(true);
        return result.value.length();
    }

    /**
     * Simple KEYS (prefer SCAN in production paths).
     * Supports: *, prefix*
     */
    public Set<String> keys(String pattern) {
        long now = System.currentTimeMillis();
        return data.entrySet().stream()
                .filter(e -> !e.getValue().isExpired(now))
                .map(Map.Entry::getKey)
                .filter(k -> match(pattern, k))
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Cursor-based SCAN (Redis-style).
     *
     * @param cursor opaque cursor (0 to start)
     * @param pattern match pattern (* or prefix*)
     * @param count hint for how many keys to examine
     * @return ScanResult with nextCursor and keys
     */
    public ScanResult scan(long cursor, String pattern, int count) {
        if (count < 1) count = 10;

        List<String> allKeys = new ArrayList<>(data.keySet());
        int size = allKeys.size();
        if (size == 0) {
            return new ScanResult(0, List.of());
        }

        int start = (int) (cursor % size);
        if (start < 0) start = 0;

        long now = System.currentTimeMillis();
        List<String> result = new ArrayList<>(Math.min(count, size));
        int examined = 0;
        int i = start;

        while (examined < count && examined < size) {
            String key = allKeys.get(i);
            Entry e = data.get(key);
            if (e != null && !e.isExpired(now) && match(pattern, key)) {
                result.add(key);
            }
            examined++;
            i++;
            if (i >= size) {
                // wrapped → done
                return new ScanResult(0, result);
            }
        }

        long next = i;
        if (next >= size) next = 0;
        return new ScanResult(next, result);
    }

    public static final class ScanResult {
        public final long nextCursor;
        public final List<String> keys;

        public ScanResult(long nextCursor, List<String> keys) {
            this.nextCursor = nextCursor;
            this.keys = Collections.unmodifiableList(keys);
        }
    }

    private static boolean match(String pattern, String key) {
        if (pattern == null || pattern.equals("*")) return true;
        if (pattern.endsWith("*") && !pattern.substring(0, pattern.length() - 1).contains("*")) {
            return key.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return key.equals(pattern);
    }

    public void flush() {
        data.clear();
        dirty.set(true);
    }

    public int size() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (Entry e : data.values()) {
            if (!e.isExpired(now)) count++;
        }
        return count;
    }

    public boolean isDirty() {
        return dirty.get();
    }

    // ──────────────────────────────────────────────────────────────
    // Persistence (version 2 + CRC32)
    // ──────────────────────────────────────────────────────────────

    /**
     * Safe load: on any failure, rename corrupt file and start empty.
     */
    private void loadSafely() {
        if (!Files.exists(dataFile)) {
            return;
        }

        try {
            load();
        } catch (Exception e) {
            loadRecoveries.incrementAndGet();
            System.err.println("[miniredis] data file load failed (" + e.getMessage()
                    + "). Moving to " + corruptBackupFile.getFileName() + " and starting empty.");
            try {
                Files.move(dataFile, corruptBackupFile,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveEx) {
                System.err.println("[miniredis] could not move corrupt file: " + moveEx.getMessage());
                try {
                    Files.deleteIfExists(dataFile);
                } catch (IOException ignored) {
                }
            }
            data.clear();
        }
    }

    public synchronized void load() throws IOException {
        if (!Files.exists(dataFile)) return;

        byte[] bytes = Files.readAllBytes(dataFile);
        if (bytes.length < 16) {
            throw new IOException("File too small");
        }

        // Verify CRC32 of payload (everything except last 8 bytes)
        // Format: [magic:4][version:4][count:4][entries...][crc:8]
        if (bytes.length < 20) {
            throw new IOException("File too small for header+crc");
        }

        long storedCrc = ((long) (bytes[bytes.length - 8] & 0xFF) << 56)
                | ((long) (bytes[bytes.length - 7] & 0xFF) << 48)
                | ((long) (bytes[bytes.length - 6] & 0xFF) << 40)
                | ((long) (bytes[bytes.length - 5] & 0xFF) << 32)
                | ((long) (bytes[bytes.length - 4] & 0xFF) << 24)
                | ((long) (bytes[bytes.length - 3] & 0xFF) << 16)
                | ((long) (bytes[bytes.length - 2] & 0xFF) << 8)
                | ((long) (bytes[bytes.length - 1] & 0xFF));

        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length - 8);
        if (crc.getValue() != storedCrc) {
            throw new IOException("CRC32 mismatch (file corrupted)");
        }

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new java.io.ByteArrayInputStream(bytes, 0, bytes.length - 8)))) {

            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("Invalid magic");
            }
            int version = in.readInt();
            if (version != 1 && version != 2) {
                throw new IOException("Unsupported version: " + version);
            }

            int count = in.readInt();
            if (count < 0) {
                throw new IOException("Invalid count");
            }

            long now = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                String key = in.readUTF();
                String value = in.readUTF();
                long expireAt = in.readLong();
                long lastAccess = now;
                if (version >= 2) {
                    lastAccess = in.readLong();
                }

                if (expireAt == 0 || expireAt > now) {
                    data.put(key, new Entry(value, expireAt, lastAccess));
                }
            }
        }

        dirty.set(false);
    }

    public synchronized void save() {
        if (closed.get()) return;

        long start = System.currentTimeMillis();
        long now = start;

        // Weakly consistent snapshot
        List<Map.Entry<String, Entry>> snapshot = new ArrayList<>(data.entrySet());
        List<Map.Entry<String, Entry>> live = new ArrayList<>(snapshot.size());
        for (Map.Entry<String, Entry> e : snapshot) {
            if (!e.getValue().isExpired(now)) {
                live.add(e);
            }
        }

        try {
            Files.createDirectories(dataFile.toAbsolutePath().getParent() == null
                    ? Path.of(".")
                    : dataFile.toAbsolutePath().getParent());
        } catch (Exception ignored) {
        }

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(
                        tempFile,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)))) {

            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(live.size());

            for (Map.Entry<String, Entry> e : live) {
                Entry entry = e.getValue();
                out.writeUTF(e.getKey());
                out.writeUTF(entry.value);
                out.writeLong(entry.expireAt);
                out.writeLong(entry.lastAccess);
            }
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Could not save data", e);
        }

        // Append CRC32 of the temp file content
        try {
            byte[] payload = Files.readAllBytes(tempFile);
            CRC32 crc = new CRC32();
            crc.update(payload);
            long crcValue = crc.getValue();

            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(
                            tempFile,
                            StandardOpenOption.APPEND)))) {
                out.writeLong(crcValue);
            }

            try {
                Files.move(tempFile, dataFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tempFile, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }

            dirty.set(false);
            lastSaveTime.set(System.currentTimeMillis());
            lastSaveDurationMs.set(System.currentTimeMillis() - start);
            lastSaveKeys.set(live.size());

        } catch (IOException e) {
            throw new IllegalStateException("Could not finalize data file", e);
        }
    }

    private void safeSave() {
        if (closed.get()) return;
        if (!dirty.get()) return; // skip if nothing changed
        try {
            save();
        } catch (Exception e) {
            System.err.println("[miniredis] background save failed: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Expiration + eviction
    // ──────────────────────────────────────────────────────────────

    private void activeExpire() {
        if (closed.get() || data.isEmpty()) return;

        long now = System.currentTimeMillis();
        int sample = Math.min(config.expirationSampleSize, data.size());
        if (sample <= 0) return;

        // Sample without copying entire key set when possible
        Iterator<String> it = data.keySet().iterator();
        int seen = 0;
        int expired = 0;

        // Reservoir-style: walk a limited number of keys
        while (it.hasNext() && seen < sample * 3) {
            String key = it.next();
            Entry e = data.get(key);
            seen++;
            if (e != null && e.isExpired(now)) {
                if (data.remove(key, e)) {
                    expiredKeys.increment();
                    expired++;
                    dirty.set(true);
                }
            }
            if (expired >= sample) break;
        }
    }

    private void ensureCapacity() {
        if (config.maxKeys <= 0) return;
        while (data.size() >= config.maxKeys) {
            evictOne();
        }
    }

    /** Approximate LRU: pick a small sample, evict least recently accessed. */
    private void evictOne() {
        if (data.isEmpty()) return;

        final int sampleSize = Math.min(5, data.size());
        String victimKey = null;
        long oldestAccess = Long.MAX_VALUE;

        Iterator<Map.Entry<String, Entry>> it = data.entrySet().iterator();
        int checked = 0;
        while (it.hasNext() && checked < sampleSize * 4) {
            Map.Entry<String, Entry> e = it.next();
            checked++;
            long access = e.getValue().lastAccess;
            if (access < oldestAccess) {
                oldestAccess = access;
                victimKey = e.getKey();
            }
            if (checked >= sampleSize && victimKey != null) {
                // enough sample
                break;
            }
        }

        if (victimKey != null && data.remove(victimKey) != null) {
            evictedKeys.increment();
            dirty.set(true);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Stats
    // ──────────────────────────────────────────────────────────────

    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keys", size());
        m.put("raw_entries", data.size());
        m.put("hits", hits.sum());
        m.put("misses", misses.sum());
        m.put("expired_keys", expiredKeys.sum());
        m.put("evicted_keys", evictedKeys.sum());
        m.put("dirty", dirty.get());
        m.put("last_save_time", lastSaveTime.get());
        m.put("last_save_duration_ms", lastSaveDurationMs.get());
        m.put("last_save_keys", lastSaveKeys.get());
        m.put("load_recoveries", loadRecoveries.get());
        m.put("max_keys", config.maxKeys);
        m.put("rdb_version", VERSION);
        return Collections.unmodifiableMap(m);
    }

    // ──────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (config.saveOnClose) {
            try {
                save();
            } catch (Exception e) {
                System.err.println("[miniredis] final save failed: " + e.getMessage());
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Validation
    // ──────────────────────────────────────────────────────────────

    private void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Key cannot be empty");
        }
        if (key.length() > 512) {
            throw new IllegalArgumentException("Key too long (max 512)");
        }
    }

    private void validateValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
    }
}