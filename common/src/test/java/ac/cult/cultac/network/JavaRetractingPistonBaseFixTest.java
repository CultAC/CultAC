package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
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
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A player standing on the arm of an east-facing piston as it retracts. PistonMovingBlockEntity#moveCollidedEntities
 * shoves the player toward the base by the plate's sweep plus 0.01, then fixEntityWithinPistonBase moves the shoved
 * box back out of the base by its depth plus 0.01, capped at the tick's progress, plus 0.01: a net 0.02 along the
 * facing. Only a shoved box gets the fix.
 */
class JavaRetractingPistonBaseFixTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final BlockPos BASE = new BlockPos(1, FLOOR_Y, 0);
    private static final int PISTON = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "east");
    private static final int EXTENDED = REGISTRY.with(PISTON, "extended", "true");
    private static final int HEAD =
            REGISTRY.with(REGISTRY.block("minecraft:piston_head").defaultState(), "facing", "east");
    private static final Vec3 ON_ARM = new Vec3(2.3, FLOOR_Y + 0.625, 0.5);
    private static final double NET_FIX = 0.02D;

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void aRetractingPistonMovesAPlayerOnItsArmByTheShoveAndFix(ClientVersion version) throws Exception {
        try (var f = onArm(version)) {
            blockEvent(f, true);
            Vec3 at = ON_ARM;
            if (!reportsAfterBlockEntities(version)) assertExact(f.tick(at, true), "the move before the pass");
            at = at.add(NET_FIX, 0, 0);
            assertExact(f.tick(at, true), "the first pass");
            assertExact(f.tick(at, true), "the second pass");
            assertExact(f.tick(at, true), "after the retraction");
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void aRetractingPistonGivesNoMoveAlongItsFacingBeyondTheFix(ClientVersion version) throws Exception {
        for (double dx : new double[] {0.3, 0.5}) {
            try (var f = onArm(version)) {
                blockEvent(f, true);
                if (!reportsAfterBlockEntities(version)) assertExact(f.tick(ON_ARM, true), "the move before the pass");
                PredictionResult moved = f.tick(ON_ARM.add(dx, 0, 0), true);
                assertFalse(exact(moved), "dx " + dx + " " + describe(moved));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void theSameMoveWithoutARetractionIsRejected(ClientVersion version) throws Exception {
        for (double dx : new double[] {0.3, 0.5}) {
            try (var f = onArm(version)) {
                PredictionResult moved = f.tick(ON_ARM.add(dx, 0, 0), true);
                assertFalse(exact(moved), "dx " + dx + " " + describe(moved));
            }
        }
    }

    /** The block event's ping answered three moves late: the client processes the event right before answering it. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void aRetractionProvenThreeMovesLateMovesThePlayerAfterItsPong(ClientVersion version) throws Exception {
        try (var f = onArm(version)) {
            blockEvent(f, false);
            int proof = f.player.lastTransactionSent.get();
            Vec3 at = ON_ARM;
            for (int tick = 0; tick < 3; tick++) assertExact(f.tick(at, true), "in flight " + tick);
            f.pongThrough(proof);
            if (!reportsAfterBlockEntities(version)) assertExact(f.tick(at, true), "the move before the pass");
            at = at.add(NET_FIX, 0, 0);
            assertExact(f.tick(at, true), "the first pass");
            assertExact(f.tick(at, true), "the second pass");
            assertExact(f.tick(at, true), "after the retraction");
        }
    }

    private static boolean reportsAfterBlockEntities(ClientVersion version) {
        return version.isNewerThanOrEquals(ClientVersion.V_26_3);
    }

    private static JavaTickFixture onArm(ClientVersion version) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.compensatedWorld.updateBlock(BASE, EXTENDED);
        f.player.compensatedWorld.updateBlock(BASE.relative(Direction.EAST), HEAD);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(ON_ARM, new Vec3(0, -0.0784000015258789, 0));
        f.player.onGround = f.player.lastOnGround = true;
        f.input(NONE);
        assertExact(f.tick(ON_ARM, true), "standing on the arm");
        return f;
    }

    private static void blockEvent(JavaTickFixture f, boolean answered) {
        var packet = new ClientboundBlockEvent(BASE, 1, Direction.EAST.get3DDataValue(), REGISTRY.blockIndex(EXTENDED));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketBlockAction().onBlockEvent(event, f.player, packet);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        if (answered) f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void assertExact(PredictionResult result, String what) {
        assertTrue(exact(result), what + ": " + describe(result));
    }
}
