package ac.cult.cultac.bedrock.bridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class GeyserMovementEffectAcksTest {
    @Test
    public void returnAfterAuthFrameKeepsTheNextTickAsTheFinalBoost() {
        var acks = new GeyserMovementEffectAcks();
        acks.auth(390);
        acks.watch(-1822115790);
        acks.auth(391);
        acks.latency(-1822115790000000L);
        acks.auth(392);
        assertEquals(391, acks.release(-1822115790, 392));
    }

    @Test
    public void returnBeforeAuthFrameKeepsThatTickAsTheFinalBoost() {
        var acks = new GeyserMovementEffectAcks();
        acks.auth(13341);
        acks.watch(-1650099081);
        acks.latency(-1650099081000000L);
        acks.auth(13342);
        assertEquals(13341, acks.release(-1650099081, 13342));
    }
}
