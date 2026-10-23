package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.junit.jupiter.api.Test;

class JavaGlideStopTest {
    @Test
    void leavingScaffoldingCommitsTheGlideStopBeforeTheNextAirTick() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            // Flag 19: the preceding tick starts in scaffolding and ends in air.
            // Vanilla travelFallFlying -> travelInAir -> stopFallFlying runs on that starting block.
            var start = new Vec3(310.8187862640389, 82.17713984844517, -80.06850284157333);
            var exit = new Vec3(310.69937611759195, 82.29473985073399, -79.91850283561287);
            var end = new Vec3(310.58527805685776, 82.33158785369419, -79.75710919209625);
            prepare(player, start, ClientVersion.V_26_3);
            player.isSprinting = true;
            // The recording held forward and sprint; its air tick moves by exactly that input.
            player.packetStateData.knownInput = new KnownInput(true, false, false, false, false, false, true);
            player.compensatedWorld.updateBlock(new BlockPos(310, 82, -81), BlockIds.SCAFFOLDING.defaultState());
            var runner = player.checkManager.getSimulationProcessor();
            runner.seedStartingVelocity(new Vec3(-0.11329486970119805, 0.11760000228881837, 0.13650000935792939));

            var leaving = runner.callPrediction(exit, start, false, -346.11563F, 7.7164636F);
            assertFalse(leaving.isExempt());
            assertFalse(leaving.hasEffectiveFlags(), leaving.getFlags().toString());
            assertTrue(leaving.getSimulationContext().isGliding());
            assertEquals(
                    DesyncStatus.TRUE,
                    leaving.getSimulationContext().getWorldData().getClimbingAtStart());
            assertEquals(
                    DesyncStatus.FALSE,
                    leaving.getSimulationContext().getWorldData().getClimbing());
            assertFalse(player.isGliding);
            assertEquals(Pose.STANDING, player.pose);

            position(player, exit);
            var air = runner.callPrediction(end, exit, false, -347.68866F, 3.7288115F);
            assertFalse(air.isExempt());
            assertFalse(air.getSimulationContext().isGliding());
            assertFalse(air.hasEffectiveFlags(), air.getFlags().toString());
            assertTrue(air.getOffset() < 1.0E-5, "offset=" + air.getOffset());
            assertEquals(0.0368480029601983, air.getAcceptedClosestToTarget().y, 1.0E-12);
        }
    }

    @Test
    void modernGlideThroughVinesKeepFallFlying() throws Exception {
        assertGlideContinues(BlockIds.CAVE_VINES_PLANT.defaultState(), ClientVersion.V_26_3);
    }

    @Test
    void pre1215ScaffoldingKeepsTheLegacyGlidingBehavior() throws Exception {
        assertGlideContinues(BlockIds.SCAFFOLDING.defaultState(), ClientVersion.V_1_21_4);
    }

    @Test
    void aTickWithoutAPositionPacketStillCommitsTheGlideStop() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var start = new Vec3(4.5, 64.5, 4.5);
            prepare(player, start, ClientVersion.V_26_3);
            player.compensatedWorld.updateBlock(
                    BlockPos.containing(start.x, start.y, start.z), BlockIds.SCAFFOLDING.defaultState());
            var runner = player.checkManager.getSimulationProcessor();
            runner.seedStartingVelocity(Vec3.ZERO);

            runner.onPlayerTickEnd(null);
            assertFalse(player.isGliding);
            assertEquals(Pose.STANDING, player.pose);
        }
    }

    @Test
    void waterTravelDoesNotApplyTheDryClimbableGlideStop() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var start = new Vec3(4.5, 64.2, 4.5);
            prepare(player, start, ClientVersion.V_26_3);
            int wetScaffold = ac.cult.blocksim.data.DataTables.defaults()
                    .registry()
                    .with(BlockIds.SCAFFOLDING.defaultState(), "waterlogged", "true");
            player.compensatedWorld.updateBlock(BlockPos.containing(start.x, start.y, start.z), wetScaffold);
            // Let the fixture's block-change timing uncertainty expire at client tick boundaries.
            for (int tick = 0; tick < 3; tick++) player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            var runner = player.checkManager.getSimulationProcessor();
            runner.seedStartingVelocity(Vec3.ZERO);

            var result = runner.callPrediction(start, start, false, 0, 0);
            assertEquals(
                    DesyncStatus.TRUE,
                    result.getSimulationContext().getWorldData().getInWater());
            assertFalse(result.getSimulationContext().stopsFallFlyingAfterTravel());
            assertTrue(player.isGliding);
        }
    }

    private void assertGlideContinues(int state, ClientVersion version) throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var start = new Vec3(4.5, 64.5, 4.5);
            prepare(player, start, version);
            player.compensatedWorld.updateBlock(BlockPos.containing(start.x, start.y, start.z), state);
            var runner = player.checkManager.getSimulationProcessor();
            runner.seedStartingVelocity(Vec3.ZERO);
            // Vanilla's zero-velocity, zero-pitch glide: gravity/lift, descent conversion, steering, drag.
            var movement = new Vec3(0, (-0.02 + 0.002) * 0.98F, (0.002 - 0.0002) * 0.99F);

            var result = runner.callPrediction(start.add(movement), start, false, 0, 0);
            assertTrue(result.getSimulationContext().usesFallFlyingMovement());
            assertFalse(result.hasEffectiveFlags(), result.getFlags().toString());
            assertTrue(player.isGliding);
        }
    }

    private void prepare(CultPlayer player, Vec3 start, ClientVersion version) throws Exception {
        var resolved = CultPlayer.class.getDeclaredField("resolvedClientVersion");
        resolved.setAccessible(true);
        resolved.set(player, version);
        for (int x = ((int) Math.floor(start.x) >> 4) - 1; x <= ((int) Math.floor(start.x) >> 4) + 1; x++) {
            for (int z = ((int) Math.floor(start.z) >> 4) - 1; z <= ((int) Math.floor(start.z) >> 4) + 1; z++) {
                player.compensatedWorld.ensureValidationChunkLoaded(x, z);
            }
        }
        player.gamemode = GameMode.SURVIVAL;
        player.isGliding = true;
        player.refreshPlayerPose();
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
        player.checkManager.getSimulationProcessor().handleRespawn();
        position(player, start);
    }

    private void position(CultPlayer player, Vec3 position) {
        player.x = player.lastX = position.x;
        player.y = player.lastY = position.y;
        player.z = player.lastZ = position.z;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, position.x, position.y, position.z);
    }
}
