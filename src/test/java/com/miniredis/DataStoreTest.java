package com.miniredis;

import com.miniredis.store.DataStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Production-oriented tests for DataStore.
 *
 * Covers:
 *  - basic CRUD + TTL
 *  - NX/XX
 *  - concurrent INCR
 *  - persistence round-trip + CRC
 *  - corrupt file recovery
 *  - SCAN
 *  - maxKeys eviction
 */
class DataStoreTest {

    @TempDir
    Path tempDir;

    private DataStore store;
    private Path dataFile;

    @BeforeEach
    void setUp() {
        dataFile = tempDir.resolve("test-redis.data");
        store = new DataStore(new DataStore.Config()
                .dataFile(dataFile)
                .saveIntervalSeconds(3600) // don't background-save during tests
                .activeExpiration(false)   // control expiration manually in tests
                .maxKeys(0)
                .saveOnClose(false)
        );
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    // ─── Basic ───────────────────────────────────────────────────

    @Test
    void setAndGet() {
        store.set("k", "v");
        assertEquals("v", store.get("k"));
    }

    @Test
    void getMissingReturnsNull() {
        assertNull(store.get("missing"));
    }

    @Test
    void delete() {
        store.set("k", "v");
        assertTrue(store.delete("k"));
        assertFalse(store.delete("k"));
        assertNull(store.get("k"));
    }

    @Test
    void exists() {
        assertFalse(store.exists("k"));
        store.set("k", "v");
        assertTrue(store.exists("k"));
    }

    @Test
    void emptyKeyRejected() {
        assertThrows(IllegalArgumentException.class, () -> store.set("", "v"));
        assertThrows(IllegalArgumentException.class, () -> store.set("  ", "v"));
        assertThrows(IllegalArgumentException.class, () -> store.set(null, "v"));
    }

    @Test
    void nullValueRejected() {
        assertThrows(IllegalArgumentException.class, () -> store.set("k", null));
    }

    // ─── TTL ─────────────────────────────────────────────────────

    @Test
    void expireAndTtl() throws InterruptedException {
        store.set("k", "v");
        assertEquals(-1, store.ttl("k"));

        assertTrue(store.expire("k", 1));
        long ttl = store.ttl("k");
        assertTrue(ttl == 0 || ttl == 1);

        Thread.sleep(1100);
        assertNull(store.get("k"));
        assertEquals(-2, store.ttl("k"));
    }

    @Test
    void setWithTtl() throws InterruptedException {
        store.set("k", "v", 1);
        assertTrue(store.ttl("k") >= 0);
        Thread.sleep(1100);
        assertNull(store.get("k"));
    }

    @Test
    void persistRemovesTtl() {
        store.set("k", "v", 60);
        assertTrue(store.ttl("k") > 0);
        assertTrue(store.persist("k"));
        assertEquals(-1, store.ttl("k"));
    }

    // ─── SET NX / XX ─────────────────────────────────────────────

    @Test
    void setNx() {
        assertTrue(store.set("k", "1", true, false, 0));
        assertFalse(store.set("k", "2", true, false, 0));
        assertEquals("1", store.get("k"));
    }

    @Test
    void setXx() {
        assertFalse(store.set("k", "1", false, true, 0));
        store.set("k", "1");
        assertTrue(store.set("k", "2", false, true, 0));
        assertEquals("2", store.get("k"));
    }

    // ─── Counters ────────────────────────────────────────────────

    @Test
    void incrAndIncrBy() {
        assertEquals(1, store.incr("c"));
        assertEquals(2, store.incr("c"));
        assertEquals(12, store.incrBy("c", 10));
        assertEquals(11, store.decr("c"));
    }

    @Test
    void incrOnNonIntegerFails() {
        store.set("c", "hello");
        assertThrows(IllegalArgumentException.class, () -> store.incr("c"));
    }

    @Test
    void concurrentIncr() throws Exception {
        final int threads = 20;
        final int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    store.incr("counter");
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertEquals(threads * perThread, Long.parseLong(store.get("counter")));
    }

    // ─── MSET-style / append ─────────────────────────────────────

    @Test
    void append() {
        assertEquals(5, store.append("k", "hello"));
        assertEquals(11, store.append("k", " world"));
        assertEquals("hello world", store.get("k"));
    }

    // ─── SCAN / KEYS ─────────────────────────────────────────────

    @Test
    void keysAndScan() {
        store.set("user:1", "a");
        store.set("user:2", "b");
        store.set("session:1", "c");

        assertEquals(2, store.keys("user:*").size());
        assertEquals(3, store.keys("*").size());

        DataStore.ScanResult r = store.scan(0, "*", 100);
        assertTrue(r.keys.size() >= 3);
    }

    // ─── maxKeys eviction ────────────────────────────────────────

    @Test
    void maxKeysEvicts() {
        store.close();
        store = new DataStore(new DataStore.Config()
                .dataFile(tempDir.resolve("evict.data"))
                .saveIntervalSeconds(3600)
                .activeExpiration(false)
                .maxKeys(3)
                .saveOnClose(false)
        );

        store.set("a", "1");
        store.set("b", "2");
        store.set("c", "3");
        store.set("d", "4"); // should trigger eviction

        assertTrue(store.size() <= 3);
    }

    // ─── Persistence ─────────────────────────────────────────────

    @Test
    void saveAndLoadRoundTrip() {
        store.set("name", "rahul");
        store.set("count", "42");
        store.expire("count", 3600);
        store.save();

        store.close();

        // reload
        store = new DataStore(new DataStore.Config()
                .dataFile(dataFile)
                .saveIntervalSeconds(3600)
                .activeExpiration(false)
                .saveOnClose(false)
        );

        assertEquals("rahul", store.get("name"));
        assertEquals("42", store.get("count"));
        assertTrue(store.ttl("count") > 0);
    }

    @Test
    void corruptFileRecoversEmpty() throws Exception {
        store.set("k", "v");
        store.save();
        store.close();

        // Corrupt the file
        java.nio.file.Files.writeString(dataFile, "NOT_A_VALID_RDB");

        // Should not throw — recovers empty
        store = new DataStore(new DataStore.Config()
                .dataFile(dataFile)
                .saveIntervalSeconds(3600)
                .activeExpiration(false)
                .saveOnClose(false)
        );

        assertNull(store.get("k"));
        assertEquals(0, store.size());
        assertTrue((Long) store.info().get("load_recoveries") >= 1);
    }

    // ─── flush / dbsize ──────────────────────────────────────────

    @Test
    void flushAndSize() {
        store.set("a", "1");
        store.set("b", "2");
        assertEquals(2, store.size());
        store.flush();
        assertEquals(0, store.size());
    }
}