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
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A moving piston head is where PistonMovingBlockEntity's progress holds it in each client tick: Entity#collide meets
 * it there or not at all, and PistonMovingBlockEntity#moveCollidedEntities moves a box the head's sweep reaches by
 * that reach and 0.01.
 */
class MovingPistonCollisionBoundsTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final Vec3 AIR_START = new Vec3(3.3, FLOOR_Y + 10, 0.5);
    private static final BlockPos AIR_BASE = new BlockPos(1, FLOOR_Y + 10, 0);
    private static final BlockPos RISE_BASE = new BlockPos(2, FLOOR_Y + 20, 0);
    private static final Vec3 RISE_START = new Vec3(2.5, FLOOR_Y + 22.05, 0.5);
    private static final Vec3 RISE_VELOCITY = new Vec3(0, -1.4, 0.4);
    /**
     * The 0.6F wide box at x 3.3 starts 1.2E-8 inside the block the east-facing head ends in, so the second half of
     * the head's sweep reaches it: PistonMovingBlockEntity#moveCollidedEntities moves it by that reach and 0.01.
     */
    private static final double HEAD_PUSH = 3.0 - (3.3 - (double) (0.6F / 2.0F)) + 0.01;

    @Test
    void aFallBesideAnExtendingPistonFollowsGravity() throws Exception {
        try (var f = airborne(true)) {
            PredictionResult hover = f.tick(AIR_START, true);
            assertFalse(exact(hover), describe(hover));
        }
    }

    /**
     * The travel lands on the head where its progress 0 holds it, a block above the base, and meets the wall from
     * there. The block entities tick before the report: the head's first half block lifts the player by 0.5 and 0.01,
     * and that Entity#move(PISTON), stopped by nothing, leaves it off the ground.
     */
    @Test
    void aFallOntoARisingHeadMeetsTheWallFromTheHeightItLands() throws Exception {
        Vec3 landed = new Vec3(RISE_START.x, RISE_BASE.getY() + 1.0, RISE_START.z + 0.2);
        try (var f = ontoRisingHead()) {
            PredictionResult result = f.tick(landed.add(0, 0.5 + 0.01, 0), false);
            assertTrue(exact(result), describe(result));
        }
        // The head is nowhere else in that tick: a landing on a later progress of it is not the client's.
        for (double head : new double[] {1.5, 2.0}) {
            try (var f = ontoRisingHead()) {
                PredictionResult result = f.tick(new Vec3(landed.x, RISE_BASE.getY() + head, landed.z), true);
                assertFalse(exact(result), head + ": " + describe(result));
            }
        }
    }

    @Test
    void aFallBesideAnExtendingPistonDoesNotRise() throws Exception {
        try (var f = airborne(true)) {
            PredictionResult climb = f.tick(AIR_START.add(0, 0.6, 0), true);
            assertFalse(exact(climb), describe(climb));
        }
    }

    @Test
    void aFallBesideARestingPistonCannotStop() throws Exception {
        try (var f = airborne(false)) {
            PredictionResult hover = f.tick(AIR_START, true);
            assertFalse(exact(hover), describe(hover));
        }
    }

    @Test
    void aFallBesideAnExtendingPistonFallsExactly() throws Exception {
        try (var f = airborne(true)) {
            // The head's second block-entity tick sweeps its last half block.
            assertFalls(f, AIR_START, 6, 1);
        }
    }

    @Test
    void aFallBesideAPistonWhoseEventIsUnansweredFallsExactly() throws Exception {
        try (var f = airborne(false)) {
            int piston = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "east");
            blockEventUnanswered(f, AIR_BASE, 0, Direction.EAST.get3DDataValue(), piston);
            Vec3 position = AIR_START;
            double velocity = 0.0D;
            StringBuilder trace = new StringBuilder();
            boolean exact = true;
            for (int tick = 0; tick < 6; tick++) {
                if (tick == 3) f.pongThrough(f.player.lastTransactionSent.get());
                velocity = (velocity - 0.08D) * 0.98F;
                // The client handles the event before tick 3; the head's second tick sweeps its last half block.
                position = position.add(tick == 4 ? HEAD_PUSH : 0, velocity, 0);
                PredictionResult result = f.tick(position, false);
                trace.append(" t").append(tick).append('=').append(describe(result));
                exact &= exact(result);
            }
            assertTrue(exact, trace.toString());
        }
    }

    @Test
    void aPlayerStandingBesideAnExtendingPistonStaysExact() throws Exception {
        try (var f = grounded()) {
            Vec3 position = new Vec3(3.3, FLOOR_Y, 0.5);
            StringBuilder trace = new StringBuilder();
            boolean exact = true;
            for (int tick = 0; tick < 5; tick++) {
                // The head's second tick moves the player after its travel landed; Entity#move(PISTON), with no
                // vertical part to stop, leaves the ground that tick reports cleared.
                boolean pushed = tick == 1;
                if (pushed) position = position.add(HEAD_PUSH, 0, 0);
                PredictionResult result = f.tick(position, !pushed);
                trace.append(" t").append(tick).append('=').append(describe(result));
                exact &= exact(result);
            }
            assertTrue(exact, trace.toString());
        }
    }

    @Test
    void knockbackIntoAnExtendingHeadMayStopAtIt() throws Exception {
        try (var f = grounded()) {
            Vec3 position = new Vec3(3.3, FLOOR_Y, 0.5);
            f.pongThrough(f.velocity(new Vec3(-0.2, 0, 0)));
            PredictionResult result = f.tick(position, true);
            assertTrue(exact(result), describe(result));
        }
    }

    @Test
    void knockbackAwayFromAnExtendingHeadCannotBeStopped() throws Exception {
        try (var f = grounded()) {
            Vec3 position = new Vec3(3.3, FLOOR_Y, 0.5);
            f.pongThrough(f.velocity(new Vec3(0.2, 0, 0)));
            PredictionResult result = f.tick(position, true);
            assertTrue(
                    result.getOffset() > 1.0E-3 || f.player.checkManager.getKnockbackHandler().violations > 0,
                    describe(result));
        }
    }

    /** Falls from rest at {@code start}, moved east by the head's sweep in the report of tick {@code pushTick}. */
    private static void assertFalls(JavaTickFixture f, Vec3 start, int ticks, int pushTick) {
        Vec3 position = start;
        double velocity = 0.0D;
        StringBuilder trace = new StringBuilder();
        boolean exact = true;
        for (int tick = 0; tick < ticks; tick++) {
            velocity = (velocity - 0.08D) * 0.98F;
            position = position.add(tick == pushTick ? HEAD_PUSH : 0, velocity, 0);
            PredictionResult result = f.tick(position, false);
            trace.append(" t").append(tick).append('=').append(describe(result));
            exact &= exact(result);
        }
        assertTrue(exact, trace.toString());
    }

    private static JavaTickFixture airborne(boolean extend) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        int piston = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "east");
        f.player.compensatedWorld.updateBlock(AIR_BASE, piston);
        settle(f);
        f.place(AIR_START, Vec3.ZERO);
        f.input(NONE);
        PredictionResult first = f.tick(AIR_START, false);
        assertTrue(exact(first), describe(first));
        if (extend) JavaPistonSlimeLaunchTest.blockEvent(f, AIR_BASE, 0, Direction.EAST.get3DDataValue(), piston);
        return f;
    }

    /**
     * A player falling onto a piston that starts to extend upwards while moving 0.4 towards a block 2 above the base:
     * Entity#collide stops the fall on the head, wherever its progress holds it, then moves Z from that height.
     */
    private static JavaTickFixture ontoRisingHead() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        int piston = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "up");
        f.player.compensatedWorld.updateBlock(RISE_BASE, piston);
        f.player.compensatedWorld.updateBlock(
                new BlockPos(RISE_BASE.getX(), RISE_BASE.getY() + 2, RISE_BASE.getZ() + 1),
                REGISTRY.block("minecraft:stone").defaultState());
        settle(f);
        f.place(RISE_START, Vec3.ZERO);
        f.input(NONE);
        PredictionResult first = f.tick(RISE_START, false);
        assertTrue(exact(first), describe(first));
        f.place(RISE_START, RISE_VELOCITY);
        JavaPistonSlimeLaunchTest.blockEvent(f, RISE_BASE, 0, Direction.UP.get3DDataValue(), piston);
        return f;
    }

    private static JavaTickFixture grounded() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        BlockPos base = new BlockPos(1, FLOOR_Y, 0);
        int piston = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "east");
        f.player.compensatedWorld.updateBlock(base, piston);
        settle(f);
        Vec3 position = new Vec3(3.3, FLOOR_Y, 0.5);
        f.place(position, Vec3.ZERO);
        f.input(NONE);
        PredictionResult first = f.tick(position, true);
        assertTrue(exact(first), describe(first));
        JavaPistonSlimeLaunchTest.blockEvent(f, base, 0, Direction.EAST.get3DDataValue(), piston);
        return f;
    }

    private static void settle(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
    }

    private static void blockEventUnanswered(JavaTickFixture f, BlockPos pos, int action, int parameter, int state) {
        var packet = new ClientboundBlockEvent(pos, action, parameter, REGISTRY.blockIndex(state));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }
}
