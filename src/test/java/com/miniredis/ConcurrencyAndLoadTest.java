package com.miniredis;

import com.miniredis.storage.EmbeddedStorageEngine;
import com.miniredis.storage.TenantStorageEngine;
import com.miniredis.store.DataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyAndLoadTest {

    @TempDir
    Path tempDir;

    private EmbeddedStorageEngine rootEngine;

    @BeforeEach
    void setUp() {
        Path dataFile = tempDir.resolve("concurrency-test.data");
        DataStore store = new DataStore(new DataStore.Config()
                .dataFile(dataFile)
                .saveIntervalSeconds(3600)
                .activeExpiration(false)
                .saveOnClose(false)
        );
        rootEngine = new EmbeddedStorageEngine(store);
    }

    @AfterEach
    void tearDown() {
        rootEngine.close();
    }

    @Test
    @DisplayName("50 concurrent threads performing 50,000 atomic operations maintain strict integrity")
    void highConcurrencyAtomicOperations() throws Exception {
        int threadCount = 50;
        int opsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        TenantStorageEngine tenantA = new TenantStorageEngine(rootEngine, "load_proj_a", 0);
        TenantStorageEngine tenantB = new TenantStorageEngine(rootEngine, "load_proj_b", 0);

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int threadIdx = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await(); // synchronous burst start
                    TenantStorageEngine targetTenant = (threadIdx % 2 == 0) ? tenantA : tenantB;
                    for (int j = 0; j < opsPerThread; j++) {
                        targetTenant.incr("shared_counter");
                        targetTenant.set("thread_" + threadIdx + "_k", "val_" + j);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        startLatch.countDown(); // Release the hounds!

        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // 25 threads for Tenant A * 1000 ops = 25,000
        // 25 threads for Tenant B * 1000 ops = 25,000
        assertThat(tenantA.get("shared_counter")).isEqualTo("25000");
        assertThat(tenantB.get("shared_counter")).isEqualTo("25000");
    }
}
