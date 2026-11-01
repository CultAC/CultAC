package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

class ReachPositionMarginTest {
    private static final double MARGIN = 0.03125;
    private static final SimpleCollisionBox START = new SimpleCollisionBox(-0.3, 0, -0.3, 0.3, 1.95, 0.3);

    @Test
    void aJavaSnapshotTakesNoMarginUntilItsQueueFolds() {
        ReachInterpolationData data = snapshot(false);
        assertTrue(data.usesSteppedInterpolation());
        assertMargin(0, data);
        for (int move = 1; move < ReachInterpolationData.MAX_QUEUED_STEPS; move++) {
            data.appendPath(EntityPositionPath.linear(new Vec3(move * 0.25, 0, 0)), 0, 0);
        }
        assertTrue(data.hasExactPosition());
        data.appendPath(EntityPositionPath.linear(new Vec3(0, 0, 0)), 0, 0);
        data.appendPath(EntityPositionPath.linear(new Vec3(1, 0, 0)), 0, 0);
        assertFalse(data.hasExactPosition());
        assertFalse(data.copy().hasExactPosition());
        assertMargin(MARGIN, data);
    }

    @Test
    void aBedrockSnapshotKeepsTheMargin() {
        ReachInterpolationData data = snapshot(true);
        assertFalse(data.hasExactPosition());
        assertMargin(MARGIN, data);
    }

    @Test
    void anInexactJavaSnapshotTakesTheMarginAndPassesItToItsCopies() {
        ReachInterpolationData data = snapshot(false);
        data.markPositionInexact();
        assertMargin(MARGIN, data);
        assertMargin(MARGIN, data.copy());
    }

    @Test
    void aSnappedPositionTakesNoMargin() {
        ReachInterpolationData data = new ReachInterpolationData(START);
        data.markPositionInexact();
        assertMargin(0, data);
    }

    private static ReachInterpolationData snapshot(boolean bedrock) {
        CultPlayer player = mock(CultPlayer.class);
        when(player.getClientVersion()).thenReturn(ClientVersion.V_26_3);
        when(player.isBedrockMovement()).thenReturn(bedrock);
        player.packetStateData = new PacketStateData();
        PacketEntity entity = new PacketEntity(EntityTypeIds.ZOMBIE, 1);
        return new ReachInterpolationData(player, START, 0, 0, 0, 0, 0, 0, 0, false, entity, false);
    }

    private static void assertMargin(double margin, ReachInterpolationData data) {
        SimpleCollisionBox movement = data.getPossibleMovementLocationCombined();
        SimpleCollisionBox reach = data.getPossibleLocationCombined(new SimpleCollisionBox());
        assertEquals(movement.minX - margin, reach.minX, 1e-9);
        assertEquals(movement.minY - margin, reach.minY, 1e-9);
        assertEquals(movement.maxZ + margin, reach.maxZ, 1e-9);
    }
}
