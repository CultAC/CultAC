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
 * A remote actor's move is handled whole when the client answers the marker after it; the actor then lerps toward the
 * move's target by 1/3, 1/2 and the rest, one step a client tick, and holds a single position throughout.
 */
class BedrockEntityMoveTest {
    private static final int ID = 900;

    @Test
    void aMoveProvenBeforeAnyTickLerpsExactly() {
        withPlayer(player -> {
            PacketEntity entity = spawn(player, EntityTypeIds.ZOMBIE, ID);
            move(player, entity, 3, false);
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
    void aMoveToTheTargetAlreadyLerpedToRestartsTheLerp() {
        withPlayer(player -> {
            PacketEntity entity = spawn(player, EntityTypeIds.ZOMBIE, ID);
            move(player, entity, 3, false);
            entity.onMovement(player, true);
            assertEquals(1.0D, entity.clientPhysicalPosition.x, 1.0E-6D);
            move(player, entity, 3, false);
            entity.onMovement(player, true);
            assertEquals(1.0D + 2.0D / 3.0D, entity.clientPhysicalPosition.x, 1.0E-6D);
            assertSpans(player, entity, 1.0D + 2.0D / 3.0D, 1.0D + 2.0D / 3.0D);
            entity.onMovement(player, true);
            entity.onMovement(player, true);
            assertEquals(3.0D, entity.clientPhysicalPosition.x, 1.0E-6D);
        });
    }

    @Test
    void aMoveAfterASnapLerpsFromTheSnappedPosition() {
        withPlayer(player -> {
            player.compensatedWorld.putChunk(
                    0, 0, new CompensatedWorld.CachedChunk(new CompensatedWorld.CachedSection[16], 0));
            PacketEntity remote = spawn(player, EntityTypeIds.PLAYER, ID);
            // Geyser's teleport of a remote player: the teleport, then a normal move to the same position.
            move(player, remote, 10, true);
            move(player, remote, 10, false);
            remote.onMovement(player, true);
            assertEquals(10.0D, remote.clientPhysicalPosition.x, 1.0E-6D);
            assertSpans(player, remote, 10.0D, 10.0D);
        });
    }

    @Test
    void aSecondMoveHandledBeforeTheNextTickRestartsTheLerpFromTheSamePosition() {
        withPlayer(player -> {
            PacketEntity entity = spawn(player, EntityTypeIds.ZOMBIE, ID);
            move(player, entity, 3, false);
            move(player, entity, 6, false);
            entity.onMovement(player, true);
            assertEquals(2.0D, entity.clientPhysicalPosition.x, 1.0E-6D);
            assertSpans(player, entity, 2.0D, 2.0D);
        });
    }

    @Test
    void onlyAPlayersClientSnapsToATeleportHalfABlockAway() {
        withPlayer(player -> {
            player.compensatedWorld.putChunk(
                    0, 0, new CompensatedWorld.CachedChunk(new CompensatedWorld.CachedSection[16], 0));
            PacketEntity remote = spawn(player, EntityTypeIds.PLAYER, ID);
            move(player, remote, 3, true);
            remote.onMovement(player, true);
            assertEquals(3.0D, remote.clientPhysicalPosition.x, 1.0E-6D, "a far teleport snaps");
            move(player, remote, 3.25, true);
            remote.onMovement(player, true);
            assertEquals(3.0D + 0.25D / 3.0D, remote.clientPhysicalPosition.x, 1.0E-6D, "a near teleport lerps");

            PacketEntity mob = spawn(player, EntityTypeIds.ZOMBIE, ID + 1);
            move(player, mob, 3, true);
            mob.onMovement(player, true);
            assertEquals(1.0D, mob.clientPhysicalPosition.x, 1.0E-6D, "a mob lerps toward a teleport");
        });
    }

    @Test
    void aStepEndingJustBelowAWholeBlockOnGroundStartsTheNextFromIt() {
        withPlayer(player -> {
            PacketEntity entity = spawnAt(player, EntityTypeIds.ZOMBIE, ID, new Vec3(0, 63.98, 0));
            entity.handleBedrockMove(player, new Vec3(3, 64, 0), 0.0F, 0.0F, false, false, true);
            entity.onMovement(player, true);
            assertEquals(63.98667D, entity.clientPhysicalPosition.y, 1.0E-4D);
            entity.onMovement(player, true);
            // 0.0067 below 64: the next step starts from 64.
            assertEquals(63.99333D, entity.clientPhysicalPosition.y, 1.0E-4D);
            entity.onMovement(player, true);
            assertEquals(3.0D, entity.clientPhysicalPosition.x, 1.0E-6D);
            assertEquals(64.0D, entity.clientPhysicalPosition.y, 1.0E-6D);
        });
    }

    @Test
    void aStepThatOnlyReturnsToTheRaisedHeightKeepsTheShownBox() {
        withPlayer(player -> {
            PacketEntity entity = spawnAt(player, EntityTypeIds.ZOMBIE, ID, new Vec3(0, 63.98, 0));
            entity.handleBedrockMove(player, new Vec3(0, 64, 0), 0.0F, 0.0F, false, false, true);
            entity.onMovement(player, true);
            entity.onMovement(player, true);
            assertEquals(63.99333D, entity.clientPhysicalPosition.y, 1.0E-4D);
            // The last step lands on the raised 64 the actor already starts from, so nothing moves on screen.
            entity.onMovement(player, true);
            assertEquals(63.99333D, entity.clientPhysicalPosition.y, 1.0E-4D);
            assertEquals(63.99333D, entity.getPossibleMovementCollisionBoxes().minY, 1.0E-4D);
        });
    }

    @Test
    void aBoxKeptAfterARaisedStepStaysUntilTheNextMove() {
        withPlayer(player -> {
            PacketEntity entity = spawnAt(player, EntityTypeIds.ZOMBIE, ID, new Vec3(4994.5, 200, 5000.5));
            // Down without ground, then back up with it: the middle step lifts the next start to 200, so the last step
            // changes nothing and the actor keeps showing 199.99333 while it idles.
            double[][] moves = {{199.98, 0}, {200, 1}};
            for (double[] move : moves) {
                entity.handleBedrockMove(
                        player, new Vec3(4994.5, move[0], 5000.5), 0.0F, 0.0F, false, false, move[1] == 1);
                for (int i = 0; i < 20; i++) entity.onMovement(player, true);
            }
            assertEquals(199.99333D, entity.getPossibleMovementCollisionBoxes().minY, 1.0E-4D);
            // The next move lerps from 200.
            entity.handleBedrockMove(player, new Vec3(4994.5, 201, 5000.5), 0.0F, 0.0F, false, false, false);
            entity.onMovement(player, true);
            assertEquals(200.0D + 1.0D / 3.0D, entity.clientPhysicalPosition.y, 1.0E-4D);
        });
    }

    @Test
    void movesAfterAKeptBoxLerpFromTheStoredPosition() {
        withPlayer(player -> {
            PacketEntity entity = spawnAt(player, EntityTypeIds.ZOMBIE, ID, new Vec3(4994.5, 200, 5000.5));
            double[][] moves = {{199.98, 0}, {200, 1}};
            for (double[] move : moves) {
                entity.handleBedrockMove(
                        player, new Vec3(4994.5, move[0], 5000.5), 0.0F, 0.0F, false, false, move[1] == 1);
                for (int i = 0; i < 20; i++) entity.onMovement(player, true);
            }
            // Shown at 199.99333, starting from 200: the next move lerps from 200.
            entity.handleBedrockMove(player, new Vec3(4989.5, 200, 5000.5), 0.0F, 0.0F, false, false, false);
            entity.onMovement(player, true);
            // The lerp moved the actor, so its box shows the start height again.
            assertEquals(200.0D, entity.getPossibleMovementCollisionBoxes().minY, 1.0E-4D);
        });
    }

    @Test
    void aRestingBatStepsFromWhereItHangs() {
        withPlayer(player -> {
            PacketEntity bat = spawnAt(player, EntityTypeIds.BAT, ID, new Vec3(0, 64.5, 0));
            bat.setBedrockResting(true);
            bat.handleBedrockMove(player, new Vec3(0, 64.5, 0), 0.0F, 0.0F, false, false, false);
            bat.onMovement(player, true);
            assertEquals(64.5D, bat.clientPhysicalPosition.y, 1.0E-4D);
            // Hung at floor(64.5) + 1 - 0.9 = 64.1, half way back to 64.5.
            bat.onMovement(player, true);
            assertEquals(64.3D, bat.clientPhysicalPosition.y, 1.0E-4D);
            bat.onMovement(player, true);
            assertEquals(64.5D, bat.clientPhysicalPosition.y, 1.0E-4D);
        });
    }

    @Test
    void anActorLerpedIntoAChunkTheClientHasNotLoadedHoldsItsPositionUntilTheChunkLoads() {
        withPlayer(player -> {
            player.compensatedWorld.putChunk(
                    0, 0, new CompensatedWorld.CachedChunk(new CompensatedWorld.CachedSection[16], 0));
            PacketEntity entity = spawn(player, EntityTypeIds.ZOMBIE, ID);
            move(player, entity, 60, false);
            player.checkManager.endBedrockClientTick();
            assertEquals(20.0D, entity.clientPhysicalPosition.x, 1.0E-6D, "the step into chunk 1");

            move(player, entity, 3, false);
            player.checkManager.endBedrockClientTick();
            player.checkManager.endBedrockClientTick();
            assertEquals(20.0D, entity.clientPhysicalPosition.x, 1.0E-6D, "held while chunk 1 is not loaded");
            assertSpans(player, entity, 20.0D, 20.0D);

            player.compensatedWorld.putChunk(
                    1, 0, new CompensatedWorld.CachedChunk(new CompensatedWorld.CachedSection[16], 0));
            player.checkManager.endBedrockClientTick();
            assertEquals(20.0D + (3.0D - 20.0D) / 3.0D, entity.clientPhysicalPosition.x, 1.0E-5D, "resumed");
        });
    }

    @Test
    void aRemotePlayerMovedIntoAChunkTheClientHasNotLoadedIsPlacedThereAndHeldUntilATeleport() {
        withPlayer(player -> {
            player.compensatedWorld.putChunk(
                    0, 0, new CompensatedWorld.CachedChunk(new CompensatedWorld.CachedSection[16], 0));
            PacketEntity remote = spawn(player, EntityTypeIds.PLAYER, ID);
            move(player, remote, 60, false);
            player.checkManager.endBedrockClientTick();
            assertEquals(60.0D, remote.clientPhysicalPosition.x, 1.0E-6D, "a normal move into chunk 3 places it");

            move(player, remote, 3, false);
            player.checkManager.endBedrockClientTick();
            player.checkManager.endBedrockClientTick();
            assertEquals(60.0D, remote.clientPhysicalPosition.x, 1.0E-6D, "held while chunk 3 is not loaded");

            move(player, remote, 3, true);
            player.checkManager.endBedrockClientTick();
            assertEquals(3.0D, remote.clientPhysicalPosition.x, 1.0E-6D, "a teleport places it again");
            assertSpans(player, remote, 3.0D, 3.0D);
        });
    }

    private static PacketEntity spawnAt(CultPlayer player, int type, int id, Vec3 feet) {
        player.compensatedEntities.addEntity(id, type, feet, 0, 0, 0);
        PacketEntity entity = player.compensatedEntities.getEntity(id);
        assertNotNull(entity);
        return entity;
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
        entity.handleBedrockMove(player, new Vec3(x, 64, 0), 0.0F, 0.0F, false, teleport, true);
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
