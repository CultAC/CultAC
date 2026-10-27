package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoFallPseudo;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Entity#move owns onGround per move. LocalPlayer reports before block entities in 1.21.2 and after them in
 * 26.3, so NoFall's correction must use the same last move as its verdict, even when travel's ground differs.
 */
class JavaLastMoveGroundCorrectionTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();
    private static final double FALL = 0.0784000015258789;
    private static final double HALF_WIDTH = 0.6F / 2.0F;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @ParameterizedTest
    @CsvSource({"V_1_21_2, false", "V_1_21_2, true", "V_1_21_5, false", "V_1_21_5, true", "V_26_3, false", "V_26_3, true"})
    void aHorizontalPistonAfterLandingCorrectsToTheLastReportedMove(ClientVersion version, boolean forged)
            throws Exception {
        try (var f = fixture(version)) {
            BlockPos piston = new BlockPos(0, FLOOR_Y, 2);
            int state = block(f, piston, "piston", "north");
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y, 1), BLOCKS.block("minecraft:stone").defaultState());
            expireBlockChanges(f);
            Vec3 start = new Vec3(0.5, FLOOR_Y, 0.4);
            f.place(start, new Vec3(0, -FALL, 0));
            f.input(NONE);
            assertTrue(exact(f.tick(start, true)));
            blockEvent(f, piston, state, 0, 2);

            // PistonMovingBlockEntity#moveCollidedEntities: overlap with [0.5, 1] plus 0.01 northward.
            double shove = -(Math.min(start.z + HALF_WIDTH - 0.5, 0.5) + 0.01);
            boolean afterPass = version.isNewerThanOrEquals(ClientVersion.V_26_3);
            Vec3 report = afterPass ? start.add(0, 0, shove) : start;
            var result = report(f, report, forged == afterPass, forged, !afterPass);
            assertLastMove(result, version, true, false);
            if (afterPass) assertEquals(shove, JavaMovementEngine.passAfterTravel(result).movement().z, 1.0E-12);
        }
    }

    @ParameterizedTest
    @CsvSource({"V_1_21_2, false", "V_1_21_2, true", "V_1_21_5, false", "V_1_21_5, true", "V_26_3, false", "V_26_3, true"})
    void aHorizontalShulkerAfterLandingCorrectsToTheLastReportedMove(ClientVersion version, boolean forged)
            throws Exception {
        try (var f = fixture(version)) {
            BlockPos box = new BlockPos(0, FLOOR_Y + 1, 0);
            int state = block(f, box, "shulker_box", "east");
            expireBlockChanges(f);
            Vec3 start = new Vec3(1.0 + HALF_WIDTH, FLOOR_Y, 0.5);
            f.place(start, new Vec3(0, -FALL, 0));
            f.input(NONE);
            assertTrue(exact(f.tick(start, true)));
            blockEvent(f, box, state, 1, 1);

            // The first opening animation advances 0.1F; the swept lid adds 0.01 to that width.
            double shove = (double) 0.1F + 0.01;
            boolean afterPass = version.isNewerThanOrEquals(ClientVersion.V_26_3);
            Vec3 report = afterPass ? start.add(shove, 0, 0) : start;
            var result = report(f, report, forged == afterPass, forged, !afterPass);
            assertLastMove(result, version, true, false);
            if (afterPass) assertEquals(shove, JavaMovementEngine.passAfterTravel(result).movement().x, 1.0E-12);
        }
    }

    @ParameterizedTest
    @CsvSource({"V_1_21_2, false", "V_1_21_2, true", "V_1_21_5, false", "V_1_21_5, true", "V_26_3, false", "V_26_3, true"})
    void aDownwardShulkerLandingCorrectsToTheLastReportedMove(ClientVersion version, boolean forged)
            throws Exception {
        try (var f = fixture(version)) {
            BlockPos box = new BlockPos(0, FLOOR_Y + 2, 0);
            int state = block(f, box, "shulker_box", "down");
            expireBlockChanges(f);
            Vec3 start = new Vec3(0.5, FLOOR_Y + 0.18, 0.5);
            f.place(start, Vec3.ZERO);
            f.input(NONE);
            assertTrue(exact(f.tick(start, false)));
            blockEvent(f, box, state, 1, 1);

            // Travel falls 0.0784 without landing. The lid's subsequent 0.11 downward move lands on stone.
            boolean afterPass = version.isNewerThanOrEquals(ClientVersion.V_26_3);
            Vec3 report = new Vec3(start.x, afterPass ? FLOOR_Y : start.y - FALL, start.z);
            var result = report(f, report, forged != afterPass, forged, afterPass);
            assertLastMove(result, version, false, true);
            if (afterPass) {
                var pass = JavaMovementEngine.passAfterTravel(result);
                assertEquals(start.y - FALL, pass.travel().position().y, 1.0E-12);
                assertEquals(FLOOR_Y, pass.result().state().position().y, 1.0E-12);
            }
        }
    }

    private static void assertLastMove(
            PredictionResult result, ClientVersion version, boolean travelGround, boolean passGround) {
        var travel = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
        assertEquals(travelGround, travel.verticalCollisionBelow(), "travel must exercise the opposite ground state");
        var pass = JavaMovementEngine.passAfterTravel(result);
        if (version.isNewerThanOrEquals(ClientVersion.V_26_3)) {
            assertNotNull(pass);
            assertTrue(pass.result().moved(), "the later pass must execute Entity#move");
            assertEquals(passGround, pass.result().state().onGround());
        } else {
            assertNull(pass, "1.21.2 reports before the block-entity pass");
        }
    }

    private static PredictionResult report(
            JavaTickFixture f, Vec3 position, boolean claimedGround, boolean forged, boolean expectedGround) {
        var previous = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var event = f.move(position, claimedGround);
        var result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertNotNull(result);
        assertTrue(result != previous, "the report must commit a new prediction");
        assertFalse(result.isExempt(), describe(result));
        assertTrue(result.getOffset() < 1.0E-5, describe(result));
        assertEquals(forged, result.hasFlag(NoFallPseudo.class), describe(result));
        if (forged) {
            assertTrue(
                    result.getFlags().stream().allMatch(flag -> flag.getCheck() instanceof NoFallPseudo),
                    describe(result));
            assertEquals(
                    Boolean.valueOf(expectedGround), result.getDesiredOnGround(), "correction must match the verdict");
        } else {
            assertTrue(exact(result), describe(result));
            assertNull(result.getDesiredOnGround(), "an honest report needs no correction");
        }
        assertEquals(expectedGround, event.getPacket().onGround(), "the listener must correct the wire claim");
        assertEquals(
                DesyncStatus.fromBoolean(expectedGround),
                f.player.checkManager.getSimulationProcessor().getLastOnGround(),
                "the next prediction must inherit the reported phase's corrected ground");
        f.tickEnd();
        return result;
    }

    private static JavaTickFixture fixture(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.checkManager.getNoFall().setEnabled(true);
        assertTrue(f.player.checkManager.getNoFall().shouldModifyPackets());
        return f;
    }

    private static int block(JavaTickFixture f, BlockPos pos, String block, String facing) {
        int state = BLOCKS.with(BLOCKS.block("minecraft:" + block).defaultState(), "facing", facing);
        f.player.compensatedWorld.updateBlock(pos, state);
        return state;
    }

    private static void expireBlockChanges(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void blockEvent(JavaTickFixture f, BlockPos pos, int state, int action, int parameter) {
        var packet = new ClientboundBlockEvent(pos, action, parameter, BLOCKS.blockIndex(state));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketBlockAction().onBlockEvent(event, f.player, packet);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }
}
