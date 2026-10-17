package ac.cult.cultac.cloud.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.wire.IntakeWire;
import com.google.gson.Gson;
import java.util.List;
import org.junit.jupiter.api.Test;

class CloudConnectionProtocolsTest {
    private static final Gson GSON = new Gson();

    @Test
    void zstdLoadsHere() {
        assertNull(IntakeWire.zstdUnavailable());
    }

    private static final String NO_ZSTD = "java.lang.UnsatisfiedLinkError: no zstd-jni in java.library.path";

    @Test
    void aServerOffersProtocols3And5WithZstd() {
        assertEquals(
                List.of(IntakeWire.PROTOCOL_3, IntakeWire.PROTOCOL_5),
                CloudConnection.protocols(CloudSettings.SERVER_PROTOCOLS, null));
    }

    @Test
    void aServerOffersOnlyProtocol3WithoutZstd() {
        assertEquals(
                List.of(IntakeWire.PROTOCOL_3), CloudConnection.protocols(CloudSettings.SERVER_PROTOCOLS, NO_ZSTD));
    }

    @Test
    void aProxyOffersOnlyProtocol5() {
        assertEquals(List.of(IntakeWire.PROTOCOL_5), CloudConnection.protocols(CloudSettings.PROXY_PROTOCOLS, null));
    }

    @Test
    void aProxyOffersNothingWithoutZstd() {
        assertEquals(List.of(), CloudConnection.protocols(CloudSettings.PROXY_PROTOCOLS, NO_ZSTD));
    }

    @Test
    void protocol5ReadsBothRadiiFromTheAnswer() {
        CloudConnection.Terms terms = connected("{\"protocol\":5,\"chunkRadius\":3,\"sectionRadius\":1}");
        assertTrue(terms.sections());
        assertEquals(3, terms.chunkRadius());
        assertEquals(1, terms.sectionRadius());
    }

    @Test
    void protocol5RadiiDefaultTo2WhenAbsent() {
        CloudConnection.Terms terms = connected("{\"protocol\":5}");
        assertTrue(terms.sections());
        assertEquals(2, terms.chunkRadius());
        assertEquals(2, terms.sectionRadius());
    }

    @Test
    void protocol5RadiiStopAt8() {
        CloudConnection.Terms atMost = connected("{\"protocol\":5,\"chunkRadius\":8,\"sectionRadius\":8}");
        assertEquals(8, atMost.chunkRadius());
        assertEquals(8, atMost.sectionRadius());
        assertFalse(
                GSON.fromJson("{\"protocol\":5,\"chunkRadius\":8,\"sectionRadius\":8}", CloudConnection.Connected.class)
                        .radiusClamped());
        for (String json : List.of(
                "{\"protocol\":5,\"chunkRadius\":9,\"sectionRadius\":9}",
                "{\"protocol\":5,\"chunkRadius\":2147483647,\"sectionRadius\":2147483647}")) {
            CloudConnection.Terms terms = connected(json);
            assertEquals(8, terms.chunkRadius());
            assertEquals(8, terms.sectionRadius());
            assertTrue(GSON.fromJson(json, CloudConnection.Connected.class).radiusClamped());
        }
        assertEquals(0, connected("{\"protocol\":5,\"chunkRadius\":-3}").chunkRadius());
    }

    @Test
    void protocol3HasNoTerrainTerms() {
        CloudConnection.Terms terms = connected("{\"protocol\":3,\"chunkRadius\":2}");
        assertEquals(CloudConnection.Terms.PROTOCOL_3, terms);
    }

    private static CloudConnection.Terms connected(String json) {
        return GSON.fromJson(json, CloudConnection.Connected.class).terms();
    }
}
