package io.github.intisy.ai.examples;

import io.github.intisy.ai.examples.support.H2Support;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.shared.spi.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs {@link StorageDemo#roundTrip} (the one backend-agnostic put/update/get + routed-request
 * routine) against all three real backends ({@code file}, {@code memory}, {@code jdbc}/H2) and
 * asserts each produces the identical stored value and routed response. This is the proof that
 * storage is genuinely swappable: nothing in the routine or its result depends on which backend
 * was chosen.
 */
class StoreParityIntegrationTest {

    enum Backend {
        FILE, MEMORY, JDBC
    }

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @EnumSource(Backend.class)
    void roundTripIsIdenticalAcrossBackends(Backend backend) {
        Store store = storeFor(backend);

        StorageDemo.Result result = StorageDemo.roundTrip(store);

        assertEquals("{\"count\":2}", result.storedValue, "put/update/get round trip should read back the updated value");
        assertEquals(200, result.routedStatus);
        assertEquals("served m-ok", result.routedBody,
                "the routed request should fall back rl(429) -> ok and serve the assigned model, on every backend");
    }

    @Test
    void buildWithoutStorageThrowsStorageRequired() {
        // StorageDemo demonstrates this (prints the caught exception); this asserts it: storage is a
        // required, never-defaulted choice, so building without it must fail on the storage path.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> AiJava.builder().build(),
                "building an AiJava without a storage backend must throw");
        assertTrue(thrown.getMessage() != null && thrown.getMessage().contains("storage"),
                "the failure must be the storage-required path, not some unrelated exception: " + thrown.getMessage());
    }

    private Store storeFor(Backend backend) {
        if (backend == Backend.FILE) return Storage.file(tempDir.resolve("config"));
        if (backend == Backend.MEMORY) return Storage.memory();
        return Storage.jdbc(H2Support.inMemoryDataSource());
    }
}
