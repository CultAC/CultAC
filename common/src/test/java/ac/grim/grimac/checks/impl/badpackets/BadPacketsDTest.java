package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.player.GrimPlayer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BadPacketsDTest {
    @Test
    void clampsPitchWithoutChangingYaw() {
        GrimPlayer player = Mockito.mock(GrimPlayer.class);
        player.xRot = 45.0F;
        player.yRot = 100.0F;

        BadPacketsD.clampPitch(player);

        assertEquals(45.0F, player.xRot);
        assertEquals(90.0F, player.yRot);
    }

    @Test
    void clampsNegativePitch() {
        GrimPlayer player = Mockito.mock(GrimPlayer.class);
        player.yRot = -100.0F;

        BadPacketsD.clampPitch(player);

        assertEquals(-90.0F, player.yRot);
    }
}
