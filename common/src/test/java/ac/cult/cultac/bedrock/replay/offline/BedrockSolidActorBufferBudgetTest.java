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

/** Explicitly invalid diagonal claim, not a legitimate recording.  */
public final class BedrockSolidActorBufferBudgetTest {
    private static final double X = 61.5, Y = 120, Z = 21.5;

    @Test
    public void aCornerCannotSpendMoreThanTheTicksSharedBudget() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer p = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            p.gamemode = GameMode.SURVIVAL;
            int stone =
                    DataTables.defaults().registry().block("minecraft:stone").defaultState();
            for (int x = 59; x <= 65; x++)
                for (int z = 19; z <= 25; z++) {
                    p.compensatedWorld.ensureValidationChunkLoaded(x >> 4, z >> 4);
                    p.compensatedWorld.updateBlock(x, 119, z, stone);
                }
            p.compensatedEntities.addEntity(6801, EntityTypeIds.OAK_BOAT, new Vec3((float) (X + 1.11), Y, Z), 0, 0, 0);
            p.compensatedEntities.addEntity(6802, EntityTypeIds.OAK_BOAT, new Vec3(X, Y, (float) (Z + 1.11)), 0, 0, 0);
            for (int id : new int[] {6801, 6802}) {
                var boat = p.compensatedEntities.getEntity(id);
                boat.setBedrockSize(1.6F, .6F, 1F);
                boat.refreshDimensions(p);
                boat.bedrockCollidable = true;
            }
            p.x = p.lastX = X;
            p.y = p.lastY = Y;
            p.z = p.lastZ = Z;
            p.onGround = p.lastOnGround = true;
            p.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(p, X, Y, Z);
            for (long t = 100; t <= 106; t++) {
                assertEquals(0, tick(p, t, X, Z, false).observation().validationOffset(), 1e-4);
                p.checkManager.endBedrockClientTick();
            }
            for (int id : new int[] {6801, 6802}) {
                var boat = p.compensatedEntities.getEntity(id);
                var at = boat.clientPhysicalPosition;
                boat.handleBedrockMove(p, new Vec3(at.x, (float) (Y + .03), at.z), 0, 0, false, false, false);
            }
            var r = tick(p, 107, X + .05, Z + .05, true);
            var range = r.movementResult().solidActorOrderRange();
            assertNotNull(range);
            assertEquals(5, range.bufferedAxes());
            assertTrue(
                    "one diagonal claim must not spend ~0.05657 from a 0.05 shared tick budget: " + r.observation(),
                    r.observation().validationOffset() > .005);
            assertTrue(p.checkManager.getListener(BedrockMovement.class).violations > 0);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(p);
        }
    }

    private static BedrockPredictionResult tick(CultPlayer p, long t, double x, double z, boolean walking) {
        long flags = 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal();
        if (walking)
            flags |= 1L << PlayerAuthInputData.HORIZONTAL_COLLISION.ordinal() | 1L << PlayerAuthInputData.UP.ordinal();
        var velocity = new Vec3(0, -.0784F, 0);
        var f = BedrockAuthInputFrame.builder(p.playerUUID)
                .protocolVersion(2225)
                .clientTick(t)
                .inputMode(1)
                .playMode(2)
                .deviceId(3)
                .position(new Vec3((float) x, Y, (float) z))
                .packetPosition(new Vec3((float) x, 121.62001F, (float) z))
                .rotation(-45, 0, -45)
                .moveVector(0, walking ? 1 : 0)
                .delta(velocity)
                .reportedEndOfTickVelocity(velocity)
                .rawInputFlags(flags)
                .build();
        p.bedrockState.offerAuthInputFrame(f);
        var prediction = p.checkManager
                .getSimulationProcessor()
                .processBedrockAuthInputFrame(f, BedrockPredictionTrigger.OFFLINE_REPLAY);
        assertNotNull(prediction);
        return prediction.getProfileResult(BedrockPredictionResult.class);
    }
}
