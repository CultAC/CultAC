package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * x39-v40-1 (1.26.40.5): a parked boat sinking in a down bubble column was sent y 119.99998, 119.46663, 119.03709 and
 * 118.78255 on consecutive ticks; the client kept the player standing on it two ticks after Cult's lerp had lowered it.
 * x39-v40-l3d-1: after the boat came to rest the client still showed it above its last position, closing a third of
 * the gap each tick (0.0097, 0.0065, 0.0043, 0.0029, 0.0019) past the 19-tick history window.
 */
class BedrockLegacyActorEnvelopeTest {
    @Test
    void anActorStandsBetweenThePositionsSentWithinTheDelay() {
        var envelope = new BedrockLegacyActorEnvelope();
        assertEquals(0.0F, envelope.buffer(1, 934, at(119.99998), at(119.99998)), 1.0E-6);
        envelope.buffer(1, 935, at(119.46663), at(119.8222));
        envelope.buffer(1, 936, at(119.03709), at(119.5604));
        // All four positions lie within the longest delay.
        assertEquals(119.99998 - 119.2167, envelope.buffer(1, 937, at(118.78255), at(119.2167)), 1.0E-5);
    }

    @Test
    void aStoppedActorIsShownClosingAThirdOfTheGapEachTick() {
        var envelope = new BedrockLegacyActorEnvelope();
        envelope.buffer(1, 100, at(64.0), at(64.0));
        envelope.buffer(1, 101, at(63.5), at(63.8));
        // 64.0 is within the longest delay, 16 ticks and three steps, up to tick 119.
        assertEquals(0.5F, envelope.buffer(1, 119, at(63.5), at(63.5)), 1.0E-6);
        // From there the shown interval's upper end closes a third of its gap to 63.5 every tick.
        assertEquals(0.5 * 4 / 9, envelope.buffer(1, 121, at(63.5), at(63.5)), 1.0E-6);
        float later = envelope.buffer(1, 150, at(63.5), at(63.5));
        assertTrue(later > 0.0F && later < 1.0E-3F, "still closing: " + later);
    }

    @Test
    void anActorThatNeverMovedHasNoBuffer() {
        var envelope = new BedrockLegacyActorEnvelope();
        envelope.buffer(1, 100, at(10.0), at(10.0));
        assertEquals(0.0F, envelope.buffer(1, 140, at(10.0), at(10.0)), 0.0);
    }

    private static Vec3 at(double y) {
        return new Vec3(0, y, 0);
    }
}
