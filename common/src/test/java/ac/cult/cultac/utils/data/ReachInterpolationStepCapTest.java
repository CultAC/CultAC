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
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;

/** A client that never ends a tick cannot grow a stepped interpolation, nor the copy each entity move makes of it. */
class ReachInterpolationStepCapTest {
    private static final int STALLED_MOVES = ReachInterpolationData.MAX_QUEUED_STEPS * 40;

    @Test
    void stalledClientKeepsTheQueueAtItsCapAndTheQueuedTicks() throws Exception {
        ReachInterpolationData data = stepped();
        for (int move = 1; move <= STALLED_MOVES; move++) {
            data.appendPath(EntityPositionPath.linear(new Vec3(move * 0.25, 1, 0)), 0, 0);
            data = data.copy();
        }

        assertEquals(ReachInterpolationData.MAX_QUEUED_STEPS, queue(data).size());
        assertEquals(
                (float) STALLED_MOVES
                        * EntityTypeUtil.modelType(EntityTypeIds.ZOMBIE).updateInterval(),
                remainingTicks(data));
    }

    @Test
    void foldedQueueStillInterpolatesToTheLastTarget() throws Exception {
        ReachInterpolationData data = stepped();
        for (int move = 1; move <= STALLED_MOVES; move++)
            data.appendPath(EntityPositionPath.linear(new Vec3(move * 0.25, 1, 0)), 0, 0);

        int ticks = 0;
        while (data.hasActiveInterpolationTarget()) {
            data.tickMovement(true, true);
            assertTrue(++ticks < 1_000, "the client's catch-up drains the folded queue");
        }
        data.tickMovement(true, true);

        assertFalse(data.hasActiveInterpolationTarget());
        assertEquals(0.0F, remainingTicks(data));
        SimpleCollisionBox box = data.getPossibleMovementLocationCombined();
        assertEquals(STALLED_MOVES * 0.25, (box.minX + box.maxX) / 2.0D, 1e-9);
    }

    private static ReachInterpolationData stepped() {
        CultPlayer player = mock(CultPlayer.class);
        when(player.getClientVersion()).thenReturn(ClientVersion.V_26_3);
        player.packetStateData = new PacketStateData();
        PacketEntity entity = new PacketEntity(EntityTypeIds.ZOMBIE, 1);
        SimpleCollisionBox start = new SimpleCollisionBox(-0.3, 1, -0.3, 0.3, 2.95, 0.3);
        ReachInterpolationData data =
                new ReachInterpolationData(player, start, 0, 1, 0, 0, 0, 0, 0, false, entity, false);
        assertTrue(data.usesSteppedInterpolation());
        return data;
    }

    private static ArrayDeque<?> queue(ReachInterpolationData data) throws Exception {
        var field = ReachInterpolationData.class.getDeclaredField("interpolationQueue");
        field.setAccessible(true);
        return (ArrayDeque<?>) field.get(data);
    }

    private static float remainingTicks(ReachInterpolationData data) throws Exception {
        var field = ReachInterpolationData.class.getDeclaredField("remainingTicks");
        field.setAccessible(true);
        return field.getFloat(data);
    }
}
