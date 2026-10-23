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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates strict multi-tenant isolation.
 * Guarantees that tenants cannot access, mutate, scan, or flush each other's data.
 */
class TenantIsolationTest {

    @TempDir
    Path tempDir;

    private EmbeddedStorageEngine rootEngine;
    private TenantStorageEngine tenantAlpha;
    private TenantStorageEngine tenantBeta;

    @BeforeEach
    void setUp() {
        Path dataFile = tempDir.resolve("multi-tenant.data");
        DataStore store = new DataStore(new DataStore.Config()
                .dataFile(dataFile)
                .saveIntervalSeconds(3600)
                .activeExpiration(false)
                .saveOnClose(false)
        );
        rootEngine = new EmbeddedStorageEngine(store);

        tenantAlpha = new TenantStorageEngine(rootEngine, "proj_alpha", 0);
        tenantBeta = new TenantStorageEngine(rootEngine, "proj_beta", 0);
    }

    @AfterEach
    void tearDown() {
        rootEngine.close();
    }

    @Test
    @DisplayName("Identical key names in different projects must store distinct values")
    void identicalKeysAreIsolated() {
        tenantAlpha.set("user:100", "Profile Alpha");
        tenantBeta.set("user:100", "Profile Beta");

        assertThat(tenantAlpha.get("user:100")).isEqualTo("Profile Alpha");
        assertThat(tenantBeta.get("user:100")).isEqualTo("Profile Beta");
    }

    @Test
    @DisplayName("Deleting a key in Project A does not affect Project B")
    void deleteIsolation() {
        tenantAlpha.set("token", "secretA");
        tenantBeta.set("token", "secretB");

        assertThat(tenantAlpha.delete("token")).isTrue();

        assertThat(tenantAlpha.get("token")).isNull();
        assertThat(tenantBeta.get("token")).isEqualTo("secretB");
    }

    @Test
    @DisplayName("Keys and scan operations only return keys within the tenant namespace")
    void scanAndKeyIsolation() {
        tenantAlpha.set("order:1", "item1");
        tenantAlpha.set("order:2", "item2");

        tenantBeta.set("order:99", "item99");
        tenantBeta.set("payment:1", "paid");

        List<String> alphaKeys = tenantAlpha.keys("*");
        assertThat(alphaKeys).containsExactlyInAnyOrder("order:1", "order:2");
        assertThat(alphaKeys).doesNotContain("order:99", "payment:1");

        List<String> betaKeys = tenantBeta.keys("order:*");
        assertThat(betaKeys).containsExactly("order:99");
    }

    @Test
    @DisplayName("Flushing Project A's database completely leaves Project B untouched")
    void flushDbIsTenantScoped() {
        tenantAlpha.set("k1", "v1");
        tenantAlpha.set("k2", "v2");

        tenantBeta.set("k1", "v1_beta");
        tenantBeta.set("k2", "v2_beta");

        // Alpha flushes its database
        tenantAlpha.flushDb();

        assertThat(tenantAlpha.dbSize()).isEqualTo(0);
        assertThat(tenantAlpha.get("k1")).isNull();
        assertThat(tenantAlpha.get("k2")).isNull();

        // Beta is 100% intact
        assertThat(tenantBeta.dbSize()).isEqualTo(2);
        assertThat(tenantBeta.get("k1")).isEqualTo("v1_beta");
        assertThat(tenantBeta.get("k2")).isEqualTo("v2_beta");
    }

    @Test
    @DisplayName("Atomic counters in different tenants do not interfere")
    void atomicCounterIsolation() {
        tenantAlpha.incrBy("counter", 10);
        tenantBeta.incrBy("counter", 50);

        assertThat(tenantAlpha.get("counter")).isEqualTo("10");
        assertThat(tenantBeta.get("counter")).isEqualTo("50");

        tenantAlpha.incr("counter");
        assertThat(tenantAlpha.get("counter")).isEqualTo("11");
        assertThat(tenantBeta.get("counter")).isEqualTo("50");
    }
}
