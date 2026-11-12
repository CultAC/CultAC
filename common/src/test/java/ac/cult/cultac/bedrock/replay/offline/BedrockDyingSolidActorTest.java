package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.function.Consumer;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

/**
 * Protocol 2225 live capture: a death event leaves the ghast's COLLIDABLE flag set. The player stays on its top
 * through the death animation and falls only after removal. These frames reproduce that support transition.
 */
public class BedrockDyingSolidActorTest {
    private static final int GHAST = 5811;
    private static final float X = 35.5F;
    private static final float Y = 164.33128356933594F;
    private static final float Z = 14.5F;
    private static final float GRAVITY = -0.0784F;
    private static final long VERTICAL = 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal();

    @Test
    public void deathKeepsTheStillCollidableGhastAsSupport() {
        withGhast(player -> {
            var ghast = player.compensatedEntities.getEntity(GHAST);
            ghast.isDead = true;
            ghast.actionAlive = false;
            for (long tick = 104; tick < 124; tick++) {
                var prediction = tick(player, tick, Y, GRAVITY, true);
                assertEquals(0, prediction.observation().validationOffset(), 1.0E-4);
                var state = prediction.nextTickBaseState();
                assertEquals(Y, state.physicalFeetPosition().y(), 1.0E-4);
                assertEquals(GRAVITY, state.velocity().y(), 1.0E-6);
                assertTrue(state.collisionFlags().onGround());
                assertNull(prediction.movementResult().solidActorOrderRange());
                player.checkManager.endBedrockClientTick();
            }
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
        });
    }

    @Test
    public void clearingTheCollidableFlagReleasesTheSupport() {
        withGhast(player -> {
            var ghast = player.compensatedEntities.getEntity(GHAST);
            ghast.isDead = true;
            ghast.bedrockCollidable = false;
            assertFall(player);
        });
    }

    @Test
    public void removingTheGhastReleasesTheSupport() {
        withGhast(player -> {
            player.compensatedEntities.entityMap.remove(GHAST);
            assertFall(player);
        });
    }

    @Test
    public void stayingOnTheRemovedGhastIsRejected() {
        withGhast(player -> {
            player.compensatedEntities.entityMap.remove(GHAST);
            var prediction = tick(player, 104, Y, GRAVITY, true);
            assertTrue(
                    prediction.observation().toString(),
                    prediction.observation().validationOffset() > 0.05);
            assertTrue(player.checkManager.getListener(BedrockMovement.class).violations > 0);
        });
    }

    private static void assertFall(CultPlayer player) {
        float future = (GRAVITY - 0.08F) * 0.98F;
        var prediction = tick(player, 104, Y + GRAVITY, future, false);
        assertEquals(0, prediction.observation().validationOffset(), 1.0E-4);
        assertEquals(
                Y + GRAVITY,
                prediction.nextTickBaseState().physicalFeetPosition().y(),
                1.0E-4);
        assertEquals(future, prediction.nextTickBaseState().velocity().y(), 1.0E-6);
        assertFalse(prediction.nextTickBaseState().collisionFlags().onGround());
        assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
    }

    private static void withGhast(Consumer<CultPlayer> test) {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.gamemode = GameMode.SURVIVAL;
            player.compensatedWorld.ensureValidationChunkLoaded(2, 0);
            float baseY = Y - 4.0F * 1.001F;
            player.compensatedEntities.addEntity(GHAST, EntityTypeIds.HAPPY_GHAST, new Vec3(X, baseY, Z), 0, 0, 0);
            var ghast = player.compensatedEntities.getEntity(GHAST);
            ghast.setBedrockSize(4.0F, 4.0F, 1.001F);
            ghast.refreshDimensions(player);
            ghast.bedrockCollidable = true;
            player.x = player.lastX = X;
            player.y = player.lastY = Y;
            player.z = player.lastZ = Z;
            player.onGround = player.lastOnGround = true;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, X, Y, Z);
            for (long tick = 100; tick < 104; tick++) {
                var prediction = tick(player, tick, Y, GRAVITY, true);
                assertEquals(0, prediction.observation().validationOffset(), 1.0E-4);
                player.checkManager.endBedrockClientTick();
            }
            test.accept(player);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static BedrockPredictionResult tick(CultPlayer player, long tick, float y, float futureY, boolean ground) {
        var velocity = new Vec3(0, futureY, 0);
        var frame = BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2225)
                .clientTick(tick)
                .inputMode(1)
                .playMode(2)
                .deviceId(3)
                .position(new Vec3(X, y, Z))
                .packetPosition(new Vec3(X, y + 1.62001F, Z))
                .rotation(0, 60, 0)
                .moveVector(0, 0)
                .delta(velocity)
                .reportedEndOfTickVelocity(velocity)
                .rawInputFlags(ground ? VERTICAL : 0)
                .build();
        player.bedrockState.offerAuthInputFrame(frame);
        var result = player.checkManager
                .getSimulationProcessor()
                .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
        assertNotNull("tick " + tick, result);
        var prediction = result.getProfileResult(BedrockPredictionResult.class);
        assertNotNull("tick " + tick, prediction);
        return prediction;
    }
}
