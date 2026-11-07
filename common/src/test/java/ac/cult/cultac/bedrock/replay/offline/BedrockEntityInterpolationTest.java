package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.prediction.integration.BedrockEntityInterpolation;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.junit.jupiter.api.Test;

/**
 * A Bedrock client handles a remote actor's move between its answers to the markers around it and lerps toward the
 * move's target by 1/3, 1/2 and the rest. Reach accepts every state the client may be in.
 */
class BedrockEntityInterpolationTest {
    private static final int ID = 900;

    @Test
    void aMoveProvenBeforeAnyTickLerpsExactly() {
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            PacketEntity entity = spawn(player, EntityTypeIds.ZOMBIE);
            var interpolation = new BedrockEntityInterpolation(entity, false, false);
            var move = move(3, false);
            interpolation.begin(player, entity, 1, move);
            interpolation.settle(player, entity, 1, move);
            assertNull(entity.oldPacketLocation, "one state");
            interpolation.tick(player, entity);
            assertEquals(1.0D, entity.clientPhysicalPosition.x, 1.0E-6D);
            interpolation.tick(player, entity);
            assertEquals(2.0D, entity.clientPhysicalPosition.x, 1.0E-6D);
            interpolation.tick(player, entity);
            assertEquals(3.0D, entity.clientPhysicalPosition.x, 1.0E-6D);
            assertNull(entity.oldPacketLocation);
            assertTrue(interpolation.idle());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    void aMoveWhoseMarkersStraddleATickKeepsEveryStateUntilProven() {
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            PacketEntity entity = spawn(player, EntityTypeIds.ZOMBIE);
            var interpolation = new BedrockEntityInterpolation(entity, false, false);
            var move = move(3, false);
            interpolation.begin(player, entity, 1, move);
            interpolation.tick(player, entity);
            // Handled before this tick, or not yet.
            assertEquals(1.0D, entity.clientPhysicalPosition.x, 1.0E-6D);
            assertSpans(player, entity, 0.0D, 1.0D);

            interpolation.settle(player, entity, 1, move);
            // Handled before the first tick, or before the next one.
            assertSpans(player, entity, 0.0D, 1.0D);
            interpolation.tick(player, entity);
            assertEquals(2.0D, entity.clientPhysicalPosition.x, 1.0E-6D);
            assertSpans(player, entity, 1.0D, 2.0D);
            interpolation.tick(player, entity);
            assertSpans(player, entity, 2.0D, 3.0D);
            interpolation.tick(player, entity);
            assertEquals(3.0D, entity.clientPhysicalPosition.x, 1.0E-6D);
            assertNull(entity.oldPacketLocation, "both lerps ended at the target");
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    void onlyAPlayersClientSnapsToATeleportHalfABlockAway() {
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            PacketEntity remote = spawn(player, EntityTypeIds.PLAYER);
            var snapping = new BedrockEntityInterpolation(remote, true, false);
            snapping.begin(player, remote, 1, move(3, true));
            snapping.settle(player, remote, 1, move(3, true));
            assertEquals(3.0D, remote.clientPhysicalPosition.x, 1.0E-6D, "a far teleport snaps");
            snapping.begin(player, remote, 2, move(3.25, true));
            snapping.settle(player, remote, 2, move(3.25, true));
            assertEquals(3.0D, remote.clientPhysicalPosition.x, 1.0E-6D, "a near teleport lerps");
            snapping.tick(player, remote);
            assertEquals(3.0D + 0.25D / 3.0D, remote.clientPhysicalPosition.x, 1.0E-6D);

            PacketEntity mob = spawn(player, EntityTypeIds.ZOMBIE, ID + 1);
            var lerping = new BedrockEntityInterpolation(mob, false, false);
            lerping.begin(player, mob, 1, move(3, true));
            lerping.settle(player, mob, 1, move(3, true));
            assertEquals(0.0D, mob.clientPhysicalPosition.x, 1.0E-6D, "a mob lerps toward a teleport");
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    void anActorPlayedBackByTimeKeepsTheSentPositionsInReach() {
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            PacketEntity entity = spawn(player, EntityTypeIds.ZOMBIE);
            var interpolation = new BedrockEntityInterpolation(entity, false, true);
            interpolation.begin(player, entity, 1, move(3, false));
            interpolation.settle(player, entity, 1, move(3, false));
            interpolation.begin(player, entity, 2, move(6, false));
            interpolation.settle(player, entity, 2, move(6, false));
            interpolation.tick(player, entity);
            assertSpans(player, entity, 0.0D, 6.0D);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static PacketEntity spawn(CultPlayer player, int type) {
        return spawn(player, type, ID);
    }

    private static PacketEntity spawn(CultPlayer player, int type, int id) {
        player.compensatedEntities.addEntity(id, type, new Vec3(0, 64, 0), 0, 0, 0);
        PacketEntity entity = player.compensatedEntities.getEntity(id);
        assertNotNull(entity);
        return entity;
    }

    private static BedrockEntityInterpolation.Move move(double x, boolean teleport) {
        return new BedrockEntityInterpolation.Move(
                new BedrockEntityInterpolation.Target(new Vec3(x, 64, 0), 0, 0, 0, false), teleport);
    }

    // Reach accepts the actor anywhere its feet were from {@code fromX} to {@code toX}.
    private static void assertSpans(CultPlayer player, PacketEntity entity, double fromX, double toX) {
        assertNotNull(entity.oldPacketLocation, "more than one state");
        SimpleCollisionBox possible = entity.getPossibleCollisionBoxes();
        SimpleCollisionBox atRest = GetBoundingBox.getPacketEntityBoundingBox(player, 0, 64, 0, entity);
        double halfWidth = (atRest.maxX - atRest.minX) / 2.0D;
        assertEquals(fromX - halfWidth, possible.minX, 1.0E-6D, possible.toString());
        assertEquals(toX + halfWidth, possible.maxX, 1.0E-6D, possible.toString());
    }
}
