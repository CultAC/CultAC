package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
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
 * A player standing on the floor beside the arm of an east-facing piston, between its base and its plate, as it
 * retracts. PistonMovingBlockEntity#moveCollidedEntities shoves the player toward the base by the plate's sweep plus
 * 0.01, then fixEntityWithinPistonBase moves the shoved box back out of the base by its depth plus 0.01, capped at
 * the tick's progress, plus 0.01: a net 0.02 along the facing. Only a shoved box gets the fix, and only one the shove
 * left inside the base's block: a player on the arm is stepped up onto the base instead.
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
    private static final Vec3 BESIDE_ARM = new Vec3(2.3, FLOOR_Y, 1.0);
    private static final Vec3 ON_ARM = new Vec3(2.3, FLOOR_Y + 0.625, 0.5);
    private static final double NET_FIX = 0.02D;

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void aRetractingPistonMovesAPlayerBesideItsArmByTheShoveAndFix(ClientVersion version) throws Exception {
        try (var f = besideArm(version)) {
            blockEvent(f, true);
            Vec3 at = BESIDE_ARM;
            if (!reportsAfterBlockEntities(version)) assertExact(f.tick(at, true), "the move before the pass");
            at = at.add(NET_FIX, 0, 0);
            assertExact(f.tick(at, groundedAfterPass(version)), "the first pass");
            assertExact(f.tick(at, groundedAfterPass(version)), "the second pass");
            assertExact(f.tick(at, true), "after the retraction");
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void aRetractingPistonGivesNoMoveAlongItsFacingBeyondTheFix(ClientVersion version) throws Exception {
        for (double dx : new double[] {0.3, 0.5}) {
            try (var f = besideArm(version)) {
                blockEvent(f, true);
                if (!reportsAfterBlockEntities(version)) {
                    assertExact(f.tick(BESIDE_ARM, true), "the move before the pass");
                }
                PredictionResult moved = f.tick(BESIDE_ARM.add(dx, 0, 0), true);
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
            try (var f = besideArm(version)) {
                PredictionResult moved = f.tick(BESIDE_ARM.add(dx, 0, 0), true);
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
        try (var f = besideArm(version)) {
            blockEvent(f, false);
            int proof = f.player.lastTransactionSent.get();
            Vec3 at = BESIDE_ARM;
            for (int tick = 0; tick < 3; tick++) assertExact(f.tick(at, true), "in flight " + tick);
            f.pongThrough(proof);
            if (!reportsAfterBlockEntities(version)) assertExact(f.tick(at, true), "the move before the pass");
            at = at.add(NET_FIX, 0, 0);
            assertExact(f.tick(at, groundedAfterPass(version)), "the first pass");
            assertExact(f.tick(at, groundedAfterPass(version)), "the second pass");
            assertExact(f.tick(at, true), "after the retraction");
        }
    }

    /**
     * On the arm the shove meets the base 0.375 above the feet. Entity#collide steps the grounded player up onto the
     * base, whose block the box then only touches, so fixEntityWithinPistonBase leaves it there.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void aRetractingPistonStepsAPlayerOnItsArmOntoItsBase(ClientVersion version) throws Exception {
        try (var f = standing(version, ON_ARM)) {
            blockEvent(f, true);
            if (!reportsAfterBlockEntities(version)) assertExact(f.tick(ON_ARM, true), "the move before the pass");
            // The plate sweeps 0.5 west from x 2.75; the shove takes the box's east face 0.01 past it.
            Vec3 onBase = new Vec3(2.25 - 0.6F / 2 - 0.01, FLOOR_Y + 1, ON_ARM.z);
            // The step's Entity#move(PISTON) attempted no downward Y and left no vertical velocity, so neither it nor
            // the travel after it reports the ground.
            assertExact(f.tick(onBase, false), "the first pass");
        }
    }

    private static boolean reportsAfterBlockEntities(ClientVersion version) {
        return version.isNewerThanOrEquals(ClientVersion.V_26_3);
    }

    /** A 26.3 client reports after the pass, whose horizontal Entity#move(PISTON) clears the ground. */
    private static boolean groundedAfterPass(ClientVersion version) {
        return !reportsAfterBlockEntities(version);
    }

    private static JavaTickFixture besideArm(ClientVersion version) throws Exception {
        return standing(version, BESIDE_ARM);
    }

    /** The extended piston and a player at rest at {@code at}. */
    private static JavaTickFixture standing(ClientVersion version, Vec3 at) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.compensatedWorld.updateBlock(BASE, EXTENDED);
        f.player.compensatedWorld.updateBlock(BASE.relative(Direction.EAST), HEAD);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        f.place(at, new Vec3(0, -0.0784000015258789, 0));
        f.player.onGround = f.player.lastOnGround = true;
        f.input(NONE);
        assertExact(f.tick(at, true), "standing still");
        return f;
    }

    private static void blockEvent(JavaTickFixture f, boolean answered) {
        var packet = new ClientboundBlockEvent(BASE, 1, Direction.EAST.get3DDataValue(), REGISTRY.blockIndex(EXTENDED));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        if (answered) f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void assertExact(PredictionResult result, String what) {
        assertTrue(exact(result), what + ": " + describe(result));
    }
}
