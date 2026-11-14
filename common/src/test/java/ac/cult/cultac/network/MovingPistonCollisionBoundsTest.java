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
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A moving piston's box is only bounded while its progress is uncertain: Entity#collide meets it somewhere inside its
 * sweep or not at all, so each axis may stop only where some box of the sweep can stop it.
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

    @Test
    void aFallBesideAnExtendingPistonFollowsGravity() throws Exception {
        try (var f = airborne(true)) {
            PredictionResult hover = f.tick(AIR_START, true);
            assertFalse(exact(hover), describe(hover));
        }
    }

    @Test
    void aFallOntoARisingHeadMeetsTheWallFromTheHeightItLands() throws Exception {
        for (double head : new double[] {1.0, 1.5, 2.0}) {
            try (var f = ontoRisingHead()) {
                Vec3 landed = new Vec3(RISE_START.x, RISE_BASE.getY() + head, RISE_START.z);
                PredictionResult result = f.tick(landed.add(0, 0, 0.2), true);
                assertTrue(exact(result), head + ": " + describe(result));
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
            assertFalls(f, AIR_START, 6);
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
                position = position.add(0, velocity, 0);
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
                PredictionResult result = f.tick(position, true);
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

    private static void assertFalls(JavaTickFixture f, Vec3 start, int ticks) {
        Vec3 position = start;
        double velocity = 0.0D;
        StringBuilder trace = new StringBuilder();
        boolean exact = true;
        for (int tick = 0; tick < ticks; tick++) {
            velocity = (velocity - 0.08D) * 0.98F;
            position = position.add(0, velocity, 0);
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
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void blockEventUnanswered(JavaTickFixture f, BlockPos pos, int action, int parameter, int state) {
        var packet = new ClientboundBlockEvent(pos, action, parameter, REGISTRY.blockIndex(state));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketBlockAction().onBlockEvent(event, f.player, packet);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }
}
