package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.player.CultPlayer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BadPacketsDTest {
    @Test
    void clampsPitchWithoutChangingYaw() {
        CultPlayer player = Mockito.mock(CultPlayer.class);
        player.xRot = 45.0F;
        player.yRot = 100.0F;

        BadPacketsD.clampPitch(player);

        assertEquals(45.0F, player.xRot);
        assertEquals(90.0F, player.yRot);
    }

    @Test
    void clampsNegativePitch() {
        CultPlayer player = Mockito.mock(CultPlayer.class);
        player.yRot = -100.0F;

        BadPacketsD.clampPitch(player);

        assertEquals(-90.0F, player.yRot);
    }
}
