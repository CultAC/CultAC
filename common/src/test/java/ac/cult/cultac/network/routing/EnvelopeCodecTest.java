package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.network.routing.EnvelopeCodec.Drop;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnvelopeCodecTest {
    private static final byte[] KEY = "a shared network secret of some length".getBytes(StandardCharsets.UTF_8);
    private static final long NOW = 1_793_140_000_000L;

    private final EnvelopeCodec self = codec("p3", KEY);
    private final EnvelopeCodec peer = codec("p7", KEY);

    private static EnvelopeCodec codec(String instance, byte[] key) {
        return new EnvelopeCodec(key, instance, Set.of("roster", "ping")::contains);
    }

    private static Envelope envelope(String type, String from, String to, long ts) {
        JsonObject body = new JsonObject();
        body.addProperty("n", 1);
        return new Envelope(Envelope.VERSION, type, UUID.randomUUID(), from, to, ts, body);
    }

    private EnvelopeCodec.Result read(String text) {
        return self.decode(text, false, false, NOW);
    }

    @Test
    void aSignedMessageForThisInstanceOrAllRoundTrips() {
        Envelope sent = envelope("ping", "p7", "p3", NOW);
        EnvelopeCodec.Result direct = read(peer.encode(sent));
        assertNull(direct.drop());
        assertEquals(sent, direct.envelope());

        EnvelopeCodec.Result broadcast = self.decode(peer.encode(envelope("ping", "p7", "all", NOW)), true, false, NOW);
        assertNotNull(broadcast.envelope());
        assertTrue(broadcast.envelope().broadcast());
    }

    @Test
    void theSignatureCoversTheExactJsonInFrontOfIt() {
        String text = peer.encode(envelope("ping", "p7", "p3", NOW));
        int dot = text.indexOf('.');
        assertEquals(44, dot);
        String json = text.substring(dot + 1);
        assertEquals(JsonParser.parseString(json).getAsJsonObject().get("from").getAsString(), "p7");
    }

    @Test
    void dropsTextOverTheSizeLimit() {
        Envelope big = envelope("ping", "p7", "p3", NOW);
        big.body().addProperty("pad", "x".repeat(EnvelopeCodec.MAX_BYTES));
        assertEquals(Drop.TOO_LARGE, read(peer.encode(big)).drop());
        assertEquals(
                Drop.TOO_LARGE,
                read("é".repeat(EnvelopeCodec.MAX_BYTES / 2 + 1)).drop());
    }

    @Test
    void dropsABadSignature() {
        String text = peer.encode(envelope("ping", "p7", "p3", NOW));
        assertEquals(Drop.BAD_SIGNATURE, read(text.replace("\"p7\"", "\"p8\"")).drop());
        assertEquals(Drop.BAD_SIGNATURE, read(text.substring(text.indexOf('.'))).drop());
        assertEquals(
                Drop.BAD_SIGNATURE,
                read("not base64!" + text.substring(text.indexOf('.'))).drop());
        String otherKey = codec("p7", "another secret".getBytes(StandardCharsets.UTF_8))
                .encode(envelope("ping", "p7", "p3", NOW));
        assertEquals(Drop.BAD_SIGNATURE, read(otherKey).drop());
        assertEquals(
                Drop.BAD_SIGNATURE,
                read(new EnvelopeCodec(null, "p7", type -> true).encode(envelope("ping", "p7", "p3", NOW)))
                        .drop());
    }

    @Test
    void dropsMalformedText() {
        assertEquals(Drop.MALFORMED, read("no separator").drop());
        String signedGarbage = peer.encode(envelope("ping", "p7", "p3", NOW));
        assertEquals(
                Drop.MALFORMED, self.decode("." + "{\"v\":1}", false, true, NOW).drop());
        assertEquals(Drop.MALFORMED, self.decode(".not json", false, true, NOW).drop());
        assertNotNull(read(signedGarbage).envelope());
    }

    @Test
    void dropsAnotherVersion() {
        Envelope future = new Envelope(2, "ping", UUID.randomUUID(), "p7", "p3", NOW, new JsonObject());
        assertEquals(Drop.UNSUPPORTED_VERSION, read(peer.encode(future)).drop());
    }

    @Test
    void dropsAMessageForAnotherInstance() {
        assertEquals(
                Drop.NOT_ADDRESSED,
                read(peer.encode(envelope("ping", "p7", "p9", NOW))).drop());
    }

    @Test
    void dropsItsOwnMessagesOnTheBroadcastChannelOnly() {
        String own = self.encode(envelope("ping", "p3", "all", NOW));
        assertEquals(Drop.OWN_BROADCAST, self.decode(own, true, false, NOW).drop());
        assertNotNull(self.decode(self.encode(envelope("ping", "p3", "p3", NOW)), false, false, NOW)
                .envelope());
    }

    @Test
    void dropsAMessageMoreThanThirtySecondsFromTheClock() {
        assertEquals(
                Drop.STALE,
                read(peer.encode(envelope("ping", "p7", "p3", NOW - 30_001))).drop());
        assertEquals(
                Drop.STALE,
                read(peer.encode(envelope("ping", "p7", "p3", NOW + 30_001))).drop());
        assertNotNull(
                read(peer.encode(envelope("ping", "p7", "p3", NOW - 30_000))).envelope());
        assertNotNull(
                read(peer.encode(envelope("ping", "p7", "p3", NOW + 30_000))).envelope());
    }

    @Test
    void dropsAnUnknownType() {
        assertEquals(
                Drop.UNKNOWN_TYPE,
                read(peer.encode(envelope("cmd", "p7", "p3", NOW))).drop());
    }

    @Test
    void dropsAnIdSeenInTheLastMinute() {
        String text = peer.encode(envelope("ping", "p7", "p3", NOW));
        assertNotNull(read(text).envelope());
        assertEquals(Drop.REPLAYED, read(text).drop());
        assertEquals(
                Drop.REPLAYED, self.decode(text, false, false, NOW + 25_000).drop());

        Envelope old = envelope("ping", "p7", "p3", NOW + 61_000);
        String later = peer.encode(old);
        assertNotNull(self.decode(later, false, false, NOW + 61_000).envelope());
        Envelope replayedLater =
                new Envelope(Envelope.VERSION, "ping", old.id(), "p7", "p3", NOW + 122_001, new JsonObject());
        assertNotNull(self.decode(peer.encode(replayedLater), false, false, NOW + 122_001)
                .envelope());
    }

    @Test
    void aForgedCopyNeverBlocksTheGenuineMessage() {
        Envelope genuine = envelope("ping", "p7", "p3", NOW);
        String forged = codec("p7", "attacker".getBytes(StandardCharsets.UTF_8)).encode(genuine);
        assertEquals(Drop.BAD_SIGNATURE, read(forged).drop());
        assertNotNull(read(peer.encode(genuine)).envelope());
    }

    @Test
    void theBusNeedsNoSignatureAndKeepsItsOwnBroadcasts() {
        EnvelopeCodec unsigned = new EnvelopeCodec(null, "p3", Set.of("ping")::contains);
        String text = unsigned.encode(envelope("ping", "p3", "p3", NOW));
        assertTrue(text.startsWith("."));
        assertEquals(
                Drop.BAD_SIGNATURE, unsigned.decode(text, false, false, NOW).drop());
        assertNotNull(unsigned.decode(text, false, true, NOW).envelope());
        assertNotNull(self.decode(self.encode(envelope("ping", "p3", "all", NOW)), true, true, NOW)
                .envelope());
    }

    @Test
    void utf8LengthCountsBytes() {
        assertEquals(1, EnvelopeCodec.utf8Length("a"));
        assertEquals(2, EnvelopeCodec.utf8Length("é"));
        assertEquals(3, EnvelopeCodec.utf8Length("警"));
        assertEquals(4, EnvelopeCodec.utf8Length("😀"));
    }
}
