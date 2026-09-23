package ac.cult.cultac.bedrock.player;

import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BedrockMovementEffectsTest {
    @Test
    public void durationCountsActorTicksRatherThanPredictionCandidates() {
        var effects = new BedrockMovementEffects();
        assertFalse(effects.glideBoost(frame(1), true));
        effects.setGlideBoost(2);
        var first = frame(2);
        assertTrue(effects.glideBoost(first, true));
        assertTrue(effects.glideBoost(first, true));
        assertTrue(effects.glideBoost(frame(3), false));
        assertTrue(effects.glideBoost(frame(4), true));
        assertFalse(effects.glideBoost(frame(5), true));
    }

    @Test
    public void zeroDurationExpiresAfterOneFinalTravelTick() {
        var effects = new BedrockMovementEffects();
        effects.setGlideBoost(1_000_000);
        assertTrue(effects.glideBoost(frame(1408), true));
        effects.setGlideBoost(0);
        var stop = frame(1417);
        assertTrue(effects.glideBoost(stop, true));
        assertTrue(effects.glideBoost(stop, true));
        assertFalse(effects.glideBoost(frame(1418), true));
        effects.setGlideBoost(0);
        assertTrue(effects.glideBoost(frame(1419), true));
        assertFalse(effects.glideBoost(frame(1420), true));
    }

    @Test
    public void acknowledgedStopUsesTheFollowingClientTickEvenWhenSentEarlier() {
        var effects = new BedrockMovementEffects();
        effects.setGlideBoost(1_000_000);
        assertTrue(effects.glideBoost(frame(390), true));
        effects.setGlideBoost(0, 391);
        assertTrue(effects.glideBoost(frame(392), true));
        assertFalse(effects.glideBoost(frame(393), true));

        effects.setGlideBoost(1_000_000);
        assertTrue(effects.glideBoost(frame(13341), true));
        effects.setGlideBoost(0, 13341);
        assertTrue(effects.glideBoost(frame(13342), true));
        assertFalse(effects.glideBoost(frame(13343), true));
    }

    @Test
    public void refreshReplacesDurationAndClearRemovesIndefiniteEffect() {
        var effects = new BedrockMovementEffects();
        effects.setGlideBoost(10);
        assertTrue(effects.glideBoost(frame(1), true));
        effects.setGlideBoost(1);
        assertTrue(effects.glideBoost(frame(2), true));
        assertFalse(effects.glideBoost(frame(3), true));
        effects.setGlideBoost(-1);
        for (int tick = 4; tick < 50; tick++) assertTrue(effects.glideBoost(frame(tick), true));
        effects.clear();
        assertFalse(effects.glideBoost(frame(50), true));
        effects.setGlideBoost(-2);
        assertTrue(effects.glideBoost(frame(51), true));
        assertFalse(effects.glideBoost(frame(52), true));
    }

    private static BedrockAuthInputFrame frame(long tick) {
        return BedrockAuthInputFrame.builder(new UUID(0, 1)).clientTick(tick).build();
    }
}
