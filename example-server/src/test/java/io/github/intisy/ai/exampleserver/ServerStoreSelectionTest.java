package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.shared.spi.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ServerStoreSelectionTest {
    @Test
    void sqliteStorePersistsAcrossInstances(@TempDir Path dir) {
        String db = dir.resolve("t.db").toString();
        Store s1 = ServerMain.storeFor("sqlite", db);
        s1.put("k.json", "{\"v\":1}");
        Store s2 = ServerMain.storeFor("sqlite", db);
        assertEquals("{\"v\":1}", s2.get("k.json"));
    }

    @Test
    void memoryStoreIsEphemeralButValid() {
        Store m = ServerMain.storeFor("memory", "ignored");
        m.put("a.json", "1");
        assertEquals("1", m.get("a.json"));
    }

    @Test
    void unknownKindThrowsInsteadOfSilentlyFallingBackToMemory() {
        assertThrows(IllegalArgumentException.class, () -> ServerMain.storeFor("sqlit", "x"));
    }

    @Test
    void jdbcWithoutUrlThrows() {
        assertThrows(IllegalArgumentException.class, () -> ServerMain.storeFor("jdbc", null));
        assertThrows(IllegalArgumentException.class, () -> ServerMain.storeFor("jdbc", "  "));
    }

    @Test
    void jdbcWithUrlRoundTrips(@TempDir Path dir) {
        String url = "jdbc:sqlite:" + dir.resolve("g.db");
        Store s1 = ServerMain.storeFor("jdbc", url);
        s1.put("k.json", "{\"v\":2}");
        Store s2 = ServerMain.storeFor("jdbc", url);
        assertEquals("{\"v\":2}", s2.get("k.json"));
    }
}
