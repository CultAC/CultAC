package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

/** Deliberate invalid claims exercise enforcement; these packets are not legitimate client recordings. */
public class BedrockSolidActorBufferLegalityTest {
    private static final int BOAT = 5807;
    private static final double X = 61.5;
    private static final double Y = 120;
    private static final double Z = 21.5;
    private static final double BOAT_X = (float) (X + 0.3 + 0.05 + 0.8);
    private static final long VERTICAL = 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal();
    private static final long HORIZONTAL = 1L << PlayerAuthInputData.HORIZONTAL_COLLISION.ordinal();

    @Test
    public void stationaryBoatRejectsAClaimPastItsFace() {
        exercise(0);
    }

    @Test
    public void rotationOnlyBoatRejectsTheSameClaim() {
        exercise(1);
    }

    @Test
    public void identicalMoveRejectsTheSameClaim() {
        exercise(2);
    }

    @Test
    public void movingBoatStillOffersTheAuthorizedBuffer() {
        exercise(3);
    }

    @Test
    public void aGroundedRotationUpdateDoesNotBorrowTheBuffer() {
        exercise(4);
    }

    // A boat a lerp step off along Y can stand no farther off along X: the X overclaim is past anything it allows.
    @Test
    public void verticalMovementLendsNothingToAnotherAxis() {
        exercise(5);
    }

    @Test
    public void sidewaysMovementLendsNothingToAnotherAxis() {
        exercise(6);
    }

    @Test
    public void rotationNearTheOriginDoesNotBorrowTheBuffer() {
        exercise(7);
    }

    private static void exercise(int mode) {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.gamemode = GameMode.SURVIVAL;
            double z = mode == 7 ? 1.0E-15F : Z;
            int stone =
                    DataTables.defaults().registry().block("minecraft:stone").defaultState();
            for (int x = 59; x <= 65; x++)
                for (int groundZ = (int) Math.floor(z) - 2; groundZ <= (int) Math.floor(z) + 2; groundZ++) {
                    player.compensatedWorld.ensureValidationChunkLoaded(x >> 4, groundZ >> 4);
                    player.compensatedWorld.updateBlock(x, 119, groundZ, stone);
                }
            player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, new Vec3(BOAT_X, Y, z), 0, 0, 0);
            var boat = player.compensatedEntities.getEntity(BOAT);
            boat.setBedrockSize(1.6F, 0.6F, 1.0F);
            boat.refreshDimensions(player);
            boat.bedrockCollidable = true;
            player.x = player.lastX = X;
            player.y = player.lastY = Y;
            player.z = player.lastZ = z;
            player.onGround = player.lastOnGround = true;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, X, Y, z);
            for (long tick = 100; tick <= 106; tick++) {
                var idle = tick(player, tick, X, z, false);
                assertEquals(0, idle.observation().validationOffset(), 1.0E-4);
                player.checkManager.endBedrockClientTick();
            }
            if (mode != 0)
                boat.handleBedrockMove(
                        player,
                        new Vec3(
                                (float) (BOAT_X + (mode == 3 ? 0.03 : 0)), (float) (Y + (mode == 5 ? 0.03 : 0)), (float)
                                        (z + (mode == 6 ? 0.03 : 0))),
                        mode == 1 || mode == 4 || mode == 7 ? 45 : 0,
                        0,
                        false,
                        false,
                        mode == 4);
            if (mode == 1 || mode == 2 || mode == 7) {
                assertEquals(boat.clientPhysicalPosition.x, boat.newPacketLocation.targetCenterX(), 0);
                assertEquals(boat.clientPhysicalPosition.y, boat.newPacketLocation.targetMinY(), 0);
                assertEquals(boat.clientPhysicalPosition.z, boat.newPacketLocation.targetCenterZ(), 0);
            }
            long attackTick = 107;
            if (mode == 4) {
                assertEquals(0, tick(player, 107, X, z, false).observation().validationOffset(), 1.0E-4);
                player.checkManager.endBedrockClientTick();
                assertNotNull(boat.newPacketLocation.bedrockStoredFeet());
                attackTick = 108;
            }
            // Walking requests ~0.1 X; the boat stops it at +0.05. +0.07 is a deliberate 0.02 overclaim.
            var result = tick(player, attackTick, X + 0.07, z, true);
            var observation = result.observation();
            assertNotNull(observation);
            if (mode == 3) {
                assertTrue(observation.toString(), observation.validationOffset() <= 1.0E-4);
                assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
                assertNotNull(result.movementResult().solidActorOrderRange());
            } else {
                assertTrue(observation.toString(), observation.validationOffset() > 0.001);
                assertTrue(
                        "invalid movement must be enforced",
                        player.checkManager.getListener(BedrockMovement.class).violations > 0);
            }
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static BedrockPredictionResult tick(CultPlayer player, long tick, double x, double z, boolean walking) {
        var velocity = new Vec3(0, -0.0784F, 0);
        long flags = VERTICAL | (walking ? HORIZONTAL | 1L << PlayerAuthInputData.UP.ordinal() : 0);
        var frame = BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2225)
                .clientTick(tick)
                .inputMode(1)
                .playMode(2)
                .deviceId(3)
                .position(new Vec3((float) x, Y, z))
                .packetPosition(new Vec3((float) x, 121.62001F, z))
                .rotation(-90, 0, -90)
                .moveVector(0, walking ? 1 : 0)
                .delta(velocity)
                .reportedEndOfTickVelocity(velocity)
                .rawInputFlags(flags)
                .build();
        player.bedrockState.offerAuthInputFrame(frame);
        var prediction = player.checkManager
                .getSimulationProcessor()
                .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
        assertNotNull(prediction);
        var result = prediction.getProfileResult(BedrockPredictionResult.class);
        assertNotNull(result);
        assertNotNull(result.observation());
        return result;
    }
}
