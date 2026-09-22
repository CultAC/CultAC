package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.TeleportData;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PhaseTeleportTest {
    @Test public void javaAcknowledgementWithoutMovementRebasesHistory() {
        assertTeleportHistory(false);
    }

    @Test public void sleepingBedrockTeleportWithoutTravelRebasesHistory() {
        assertTeleportHistory(true);
    }

    private void assertTeleportHistory(boolean bedrock) {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = mock(CultPlayer.class);
        when(player.isBedrockMovement()).thenReturn(bedrock);
        player.isInBed = true;
        player.x = -100;
        player.y = 64;
        player.z = -100;
        Phase phase = new Phase(player);
        phase.oldBox = new SimpleCollisionBox(-101, 64, -101, -99, 66, -99);
        SimpleCollisionBox previous = phase.oldBox;
        PredictionResult result = mock(PredictionResult.class);
        TeleportData teleport = mock(TeleportData.class);
        when(teleport.getLocation()).thenReturn(new Vec3(10.5, 64, 10.5));
        when(result.getSetBackData()).thenReturn(teleport);

        phase.onPredictionComplete(new PredictionComplete(result));
        assertSame(previous, phase.oldBox);
        when(result.isTeleport()).thenReturn(true);
        phase.onPredictionComplete(new PredictionComplete(result));

        double inset = bedrock ? 0.001 : 0;
        assertEquals(10.5, (phase.oldBox.minX + phase.oldBox.maxX) / 2, 1e-10);
        assertEquals(10.5, (phase.oldBox.minZ + phase.oldBox.maxZ) / 2, 1e-10);
        assertEquals(64 + inset, phase.oldBox.minY, 1e-10);
        assertEquals(64 + (double) 1.8f - inset, phase.oldBox.maxY, 1e-10);
        assertTrue(phase.oldBox.isIntersected(new SimpleCollisionBox(10, 64, 10, 11, 65, 11)));
        assertFalse(phase.oldBox.isIntersected(new SimpleCollisionBox(11, 64, 10, 12, 65, 11)));
        assertEquals(0, phase.violations, 0);
    }
}
