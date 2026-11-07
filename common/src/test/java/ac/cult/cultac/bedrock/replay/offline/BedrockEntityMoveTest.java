package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * A Bedrock client handles a remote actor's move between its answers to the markers around it and lerps toward the
 * move's target by 1/3, 1/2 and the rest, as a pre-26.3 Java client does between two pings.
 */
class BedrockEntityMoveTest {
    private static final int ID = 900;

    @Test
    void aMoveProvenBeforeAnyTickLerpsExactly() {
        withPlayer(player -> {
            PacketEntity entity = spawn(player, EntityTypeIds.ZOMBIE, ID);
            move(player, entity, 3, false);
            entity.onSecondTransaction();
            entity.onMovement(player, true);
            assertEquals(1.0D, entity.clientPhysicalPosition.x, 1.0E-6D);
            assertSpans(player, entity, 1.0D, 1.0D);
            entity.onMovement(player, true);
            assertEquals(2.0D, entity.clientPhysicalPosition.x, 1.0E-6D);
            entity.onMovement(player, true);
            assertEquals(3.0D, entity.clientPhysicalPosition.x, 1.0E-6D);
            assertSpans(player, entity, 3.0D, 3.0D);
        });
    }

    @Test
    void aMoveWhoseMarkersStraddleATickKeepsTheOldAndNewPositionsUntilProven() {
        withPlayer(player -> {
            PacketEntity entity = spawn(player, EntityTypeIds.ZOMBIE, ID);
            move(player, entity, 3, false);
            entity.onMovement(player, true);
            // Not handled yet, or handled before this tick.
            assertSpans(player, entity, 0.0D, 1.0D);
            entity.onSecondTransaction();
            entity.onMovement(player, true);
            // Handled before the first tick, or before this one.
            assertSpans(player, entity, 1.0D, 2.0D);
            entity.onMovement(player, true);
            entity.onMovement(player, true);
            assertSpans(player, entity, 3.0D, 3.0D);
        });
    }

    @Test
    void movesSentInOneFlushKeepThePositionBeforeTheFirst() {
        withPlayer(player -> {
            PacketEntity entity = spawn(player, EntityTypeIds.ZOMBIE, ID);
            move(player, entity, 3, false);
            move(player, entity, 6, false);
            entity.onMovement(player, true);
            assertSpans(player, entity, 0.0D, 2.0D);
        });
    }

    @Test
    void onlyAPlayersClientSnapsToATeleportHalfABlockAway() {
        withPlayer(player -> {
            player.compensatedWorld.putChunk(
                    0, 0, new CompensatedWorld.CachedChunk(new CompensatedWorld.CachedSection[16], 0));
            PacketEntity remote = spawn(player, EntityTypeIds.PLAYER, ID);
            move(player, remote, 3, true);
            remote.onSecondTransaction();
            remote.onMovement(player, true);
            assertEquals(3.0D, remote.clientPhysicalPosition.x, 1.0E-6D, "a far teleport snaps");
            move(player, remote, 3.25, true);
            remote.onSecondTransaction();
            remote.onMovement(player, true);
            assertEquals(3.0D + 0.25D / 3.0D, remote.clientPhysicalPosition.x, 1.0E-6D, "a near teleport lerps");

            PacketEntity mob = spawn(player, EntityTypeIds.ZOMBIE, ID + 1);
            move(player, mob, 3, true);
            mob.onSecondTransaction();
            mob.onMovement(player, true);
            assertEquals(1.0D, mob.clientPhysicalPosition.x, 1.0E-6D, "a mob lerps toward a teleport");
        });
    }

    @Test
    void aPlayerTeleportThatMayOrMayNotSnapKeepsBothOutcomes() {
        withPlayer(player -> {
            player.compensatedWorld.putChunk(
                    0, 0, new CompensatedWorld.CachedChunk(new CompensatedWorld.CachedSection[16], 0));
            PacketEntity remote = spawn(player, EntityTypeIds.PLAYER, ID);
            move(player, remote, 0.6, false);
            remote.onMovement(player, true);
            remote.onSecondTransaction();
            // Shown somewhere from 0 to 0.2: a teleport to 0.6 is 0.4 to 0.6 away.
            move(player, remote, 0.6, true);
            remote.onSecondTransaction();
            remote.onMovement(player, true);
            // Snapped to 0.6, or a third of the way there from 0.1 to 0.2.
            assertSpans(player, remote, 0.2D, 0.6D);
        });
    }

    private static void withPlayer(Consumer<CultPlayer> test) {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            test.accept(player);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static PacketEntity spawn(CultPlayer player, int type, int id) {
        player.compensatedEntities.addEntity(id, type, new Vec3(0, 64, 0), 0, 0, 0);
        PacketEntity entity = player.compensatedEntities.getEntity(id);
        assertNotNull(entity);
        assertNull(entity.oldPacketLocation);
        return entity;
    }

    private static void move(CultPlayer player, PacketEntity entity, double x, boolean teleport) {
        entity.beginBedrockMove(player, new Vec3(x, 64, 0), 0.0F, 0.0F, false, teleport);
    }

    private static double halfWidth(CultPlayer player, PacketEntity entity) {
        SimpleCollisionBox atRest = GetBoundingBox.getPacketEntityBoundingBox(player, 0, 64, 0, entity);
        return (atRest.maxX - atRest.minX) / 2.0D;
    }

    // The client may show the actor's feet anywhere from fromX to toX.
    private static void assertSpans(CultPlayer player, PacketEntity entity, double fromX, double toX) {
        SimpleCollisionBox possible = entity.getPossibleMovementCollisionBoxes();
        double halfWidth = halfWidth(player, entity);
        assertEquals(fromX - halfWidth, possible.minX, 1.0E-6D, possible.toString());
        assertEquals(toX + halfWidth, possible.maxX, 1.0E-6D, possible.toString());
    }
}
