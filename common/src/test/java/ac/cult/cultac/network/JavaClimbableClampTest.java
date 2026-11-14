package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A 26.3 client walking east through vines. LivingEntity#travelInAir adds the input through moveRelative and then
 * LivingEntity#handleOnClimbable clamps the whole delta to 0.15 on each horizontal axis before Entity#move.
 */
class JavaClimbableClampTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final float YAW = -90.0F;
    private static final double CLAMP = 0.15F;
    private static final double GROUND_Y = -0.0784000015258789;

    /** A fast start into a vine: the move is the clamp, with the input inside it, on the ground and in the air. */
    @ParameterizedTest
    @CsvSource({"true, 0.0, true", "true, 1.0, false", "false, 0.0, true", "false, 1.0, false"})
    void theClampHoldsTheInputToo(boolean ground, double inputs, boolean vanilla) throws Exception {
        try (var f = new JavaTickFixture()) {
            int y = ground ? FLOOR_Y : FLOOR_Y + 1;
            vine(f, 0, y);
            settle(f);
            aim(f);
            f.place(new Vec3(0.5, y, 0.5), new Vec3(0.4, ground ? GROUND_Y : 0.0, 0.0));
            f.input(FORWARD);
            double input = ClientMoveInput.of(f, FORWARD, 1.0F, ground ? 0.1F : 0.02F, YAW).x;
            PredictionResult result = f.tick(new Vec3(0.5 + CLAMP + inputs * input, y, 0.5), ground);
            assertTrue(exact(result) == vanilla, describe(result));
        }
    }

    /**
     * Walking along a row of vines: each tick moves by the clamped velocity plus input, and the ground friction decays
     * that clamped delta. A walk that moves by the clamped carry plus the whole input flags.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aWalkThroughVinesMovesByTheClampedDelta(boolean vanilla) throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int x = 0; x <= 12; x++) vine(f, x, FLOOR_Y);
            settle(f);
            aim(f);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, new Vec3(0, GROUND_Y, 0));
            f.input(FORWARD);
            double walk = ClientMoveInput.of(f, FORWARD, 1.0F, 0.1F, YAW).x;
            double carry = 0.0;
            boolean allExact = true;
            StringBuilder log = new StringBuilder();
            for (int tick = 0; tick < 10; tick++) {
                double moved = vanilla ? Math.min(carry + walk, CLAMP) : Math.min(carry, CLAMP) + walk;
                position = position.add(moved, 0, 0);
                PredictionResult result = f.tick(position, true);
                allExact &= exact(result);
                log.append(tick).append(": ").append(describe(result)).append('\n');
                carry = moved * (double) (0.6F * 0.91F);
            }
            assertTrue(allExact == vanilla, log.toString());
        }
    }

    /**
     * A client sliding down a ladder column takes a knockback. Until its pong the client may not have it and slides on;
     * from the pong on, LivingEntity#handleOnClimbable clamps its X to 0.15 before the move, and the next tick carries
     * that clamped delta.
     */
    @ParameterizedTest
    @CsvSource({"0, 0.15, true", "0, 0.4, false", "3, 0.15, true", "3, 0.4, false"})
    void aKnockbackOnALadderMovesByTheClampedDelta(int pendingTicks, double hitX, boolean vanilla) throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int y = 60; y <= 80; y++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), BlockIds.LADDER.defaultState());
            }
            settle(f);
            Vec3 position = new Vec3(0.3, 75, 0.5);
            f.place(position, Vec3.ZERO);
            f.input(NONE);
            double velocityY = 0.0;
            for (int tick = 0; tick < 4 + pendingTicks; tick++) {
                if (tick == 4) f.velocity(new Vec3(0.4, 0.4, 0.0));
                double moveY = Math.max(velocityY, -0.15F);
                position = position.add(0.0, moveY, 0.0);
                PredictionResult slide = f.tick(position, false);
                assertTrue(exact(slide), tick + ": " + describe(slide));
                velocityY = (moveY - 0.08) * (double) 0.98F;
            }
            if (pendingTicks == 0) f.velocity(new Vec3(0.4, 0.4, 0.0));
            f.pongThrough(f.player.lastTransactionSent.get());
            position = position.add(hitX, 0.4, 0.0);
            PredictionResult hit = f.tick(position, false);
            assertTrue(exact(hit) == vanilla, describe(hit));
            if (!vanilla) return;
            position = position.add(CLAMP * (double) 0.91F, (0.4 - 0.08) * (double) 0.98F, 0.0);
            PredictionResult carried = f.tick(position, false);
            assertTrue(exact(carried), describe(carried));
        }
    }

    /**
     * Walking or sprinting along a row of scaffolding on the floor: scaffolding is climbable, so every tick moves by
     * the clamped delta. A move by the unclamped carry plus input flags.
     */
    @ParameterizedTest
    @CsvSource({
        "false, true, V26_3, V_26_3", "false, false, V26_3, V_26_3",
        "true, true, V26_3, V_26_3", "true, false, V26_3, V_26_3",
        "false, true, V1_21_11, V_1_21_11", "false, false, V1_21_11, V_1_21_11",
        "true, true, V1_21_11, V_1_21_11", "true, false, V1_21_11, V_1_21_11"
    })
    void aRunThroughScaffoldingMovesByTheClampedDelta(
            boolean sprint, boolean vanilla, ProtocolVersion protocol, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture(protocol, version)) {
            int scaffolding =
                    DataTables.defaults().registry().with(BlockIds.SCAFFOLDING.defaultState(), "distance", "0");
            for (int x = 0; x <= 15; x++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, 0), scaffolding);
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y + 1, 0), scaffolding);
            }
            settle(f);
            aim(f);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, new Vec3(0, GROUND_Y, 0));
            ServerboundPlayerInput input = sprint ? SPRINT : FORWARD;
            if (sprint) {
                var command =
                        new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
                new PacketEntityAction()
                        .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
            }
            f.input(input);
            float speed = sprint ? (float) ((double) 0.1F * (1.0D + (double) 0.3F)) : 0.1F;
            double walk = ClientMoveInput.of(f, input, 1.0F, speed, YAW).x;
            double carry = 0.0;
            boolean allExact = true;
            StringBuilder log = new StringBuilder();
            for (int tick = 0; tick < 12; tick++) {
                double moved = vanilla ? Math.min(carry + walk, CLAMP) : carry + walk;
                position = position.add(moved, 0, 0);
                PredictionResult result = f.tick(position, true);
                allExact &= exact(result);
                log.append(tick).append(": ").append(describe(result)).append('\n');
                carry = moved * (double) (0.6F * 0.91F);
            }
            assertTrue(allExact == vanilla, log.toString());
        }
    }

    /**
     * A run along scaffolding that carries the unclamped delta and adds the whole input each tick: the first tick past
     * the clamp is a move no input reaches, so the input check rejects it.
     */
    @ParameterizedTest
    @CsvSource({"false, V26_3, V_26_3", "true, V26_3, V_26_3", "false, V1_21_11, V_1_21_11", "true, V1_21_11, V_1_21_11"})
    void anUnclampedRunThroughScaffoldingFlags(boolean sprint, ProtocolVersion protocol, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture(protocol, version)) {
            int scaffolding =
                    DataTables.defaults().registry().with(BlockIds.SCAFFOLDING.defaultState(), "distance", "0");
            for (int x = 0; x <= 15; x++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, 0), scaffolding);
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y + 1, 0), scaffolding);
            }
            settle(f);
            aim(f);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, new Vec3(0, GROUND_Y, 0));
            ServerboundPlayerInput input = sprint ? SPRINT : FORWARD;
            if (sprint) f.command(PlayerCommandAction.START_SPRINTING, 0);
            f.input(input);
            float speed = sprint ? (float) ((double) 0.1F * (1.0D + (double) 0.3F)) : 0.1F;
            double walk = ClientMoveInput.of(f, input, 1.0F, speed, YAW).x;
            double carry = 0.0;
            for (int tick = 0; tick < 12; tick++) {
                double moved = carry + walk;
                position = position.add(moved, 0, 0);
                PredictionResult result = f.tick(position, true);
                if (moved > CLAMP + 0.001) {
                    JavaTickFixture.assertRejected(result, "Speed");
                    return;
                }
                assertTrue(exact(result), tick + ": " + describe(result));
                carry = moved * (double) (0.6F * 0.91F);
            }
            throw new AssertionError("the run never passed the clamp");
        }
    }

    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    private static void aim(JavaTickFixture f) {
        f.player.xRot = YAW;
        f.player.yRot = 0.0F;
    }

    private static void vine(JavaTickFixture f, int x, int y) {
        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, 0), BlockIds.VINE.defaultState());
    }

    private static void settle(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }
}
