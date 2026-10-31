package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A 26.3 client moves off a vine faster than the climb allows. LivingEntity#moveRelative adds the input to
 * deltaMovement and LivingEntity#handleOnClimbable then clamps it to 0.15 horizontally, both before Entity#move, so the
 * tick off the vine starts from the clamped deltaMovement.
 */
class JavaClimbableCarryTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput BACKWARD =
            new ServerboundPlayerInput(false, true, false, false, false, false, false);
    private static final float YAW = -90.0F;
    private static final double START_X = 0.4;
    // Above the clamp by less than one backward input, so the backward input ends the climbing move inside it.
    private static final double SLOW_START_X = 0.16;
    private static final double CLIMB_CLAMP = 0.15F;
    // LivingEntity#travelInAir's air friction and gravity.
    private static final double AIR_FRICTION = 0.91F;
    private static final double FALL = -0.08 * 0.98F;

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_4", "V_1_21_5", "V_1_21_9", "V_1_21_11", "V_26_1", "V_26_2", "V_26_3"})
    void aWallClimbCarriesThePostCollisionBoostAndZeroedHorizontalAxis(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            var registry = DataTables.defaults().registry();
            int ladder = registry.with(BlockIds.LADDER.defaultState(), "facing", "north");
            for (int y = 64; y <= 70; y++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), ladder);
                f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 1), BlockIds.STONE.defaultState());
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.place(new Vec3(0.5, 64, 0.5), new Vec3(0, FALL, 0));
            f.input(FORWARD);
            Vec3 end = new Vec3(0.5, 64, 0.8125 - (double) (0.6F / 2));
            PredictionResult contact = f.tick(end, true);
            assertTrue(exact(contact), describe(contact));
            Vec3 expected = new Vec3(0, (0.2 - 0.08) * 0.98F, 0);
            var carried = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
            assertTrue(
                    carried.stream().anyMatch(v -> v.distanceTo(expected) < 1e-10),
                    "the source post-collision ladder velocity: " + carried);
            PredictionResult rise = f.tick(end.add(expected), false);
            assertTrue(exact(rise), describe(rise));
        }
    }

    /**
     * The keys press into the ladder while the move slides along it. Moving along the ladder alone reaches the same
     * position without touching it, but the pressed move set horizontalCollision, so
     * LivingEntity#handleRelativeFrictionAndCalculateMovement raised the carried Y to 0.2.
     */
    @Test
    void aSlideAlongALadderWhilePressingIntoItCarriesTheClimbBoost() throws Exception {
        try (var f = new JavaTickFixture()) {
            var registry = DataTables.defaults().registry();
            int ladder = registry.with(BlockIds.LADDER.defaultState(), "facing", "north");
            for (int x = -1; x <= 2; x++) {
                for (int y = 64; y <= 70; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, 0), ladder);
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, 1), BlockIds.STONE.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 start = new Vec3(0.5, FLOOR_Y, 0.8125 - (double) (0.6F / 2));
            f.place(start, new Vec3(0, FALL, 0));
            var keys = new ServerboundPlayerInput(true, false, true, false, false, false, false);
            f.input(keys);
            float friction = 0.6F * 0.91F;
            Vec3 input = ClientMoveInput.of(f, keys, 1.0F, 0.1F * (0.21600002F / (friction * friction * friction)), 0);
            assertTrue(input.z > 0, "the keys press into the ladder: " + input);
            Vec3 slide = start.add(input.x, 0, 0);
            PredictionResult pressed = f.tick(slide, true);
            assertTrue(exact(pressed), describe(pressed));
            Vec3 expected = new Vec3(input.x * friction, (0.2 - 0.08) * 0.98F, 0);
            var carried = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
            assertTrue(
                    carried.stream().anyMatch(v -> v.distanceTo(expected) < 1e-10),
                    "the climb boost of the pressed move: " + carried);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            PredictionResult rise = f.tick(slide.add(expected), false);
            assertTrue(exact(rise), describe(rise));
        }
    }

    @Test
    void theTickOffAClimbableStartsFromTheClampedDeltaMovement() throws Exception {
        try (JavaTickFixture f = onAVine()) {
            Vec3 input = ClientMoveInput.of(f, FORWARD, 1.0F, 0.02F, YAW);
            PredictionResult climbing = climb(f, input, CLIMB_CLAMP);
            assertTrue(exact(climbing), describe(climbing));
            PredictionResult off = stepOff(f, input, CLIMB_CLAMP, CLIMB_CLAMP * AIR_FRICTION + input.x);
            assertTrue(exact(off), describe(off));
        }
    }

    @Test
    void theTickOffAClimbableDoesNotStartFromTheUnclampedDeltaMovement() throws Exception {
        try (JavaTickFixture f = onAVine()) {
            Vec3 input = ClientMoveInput.of(f, FORWARD, 1.0F, 0.02F, YAW);
            climb(f, input, CLIMB_CLAMP);
            PredictionResult off = stepOff(f, input, CLIMB_CLAMP, START_X * AIR_FRICTION + input.x);
            assertFalse(exact(off), describe(off));
        }
        try (JavaTickFixture f = onAVine()) {
            Vec3 input = ClientMoveInput.of(f, FORWARD, 1.0F, 0.02F, YAW);
            climb(f, input, CLIMB_CLAMP + input.x);
            PredictionResult off =
                    stepOff(f, input, CLIMB_CLAMP + input.x, (START_X + input.x) * AIR_FRICTION + input.x);
            assertFalse(exact(off), describe(off));
        }
    }

    @Test
    void theTickOffAClimbableStartsFromAnOpposingInputThatEndsInsideTheClamp() throws Exception {
        try (JavaTickFixture f = onAVine(SLOW_START_X, BACKWARD)) {
            Vec3 back = ClientMoveInput.of(f, BACKWARD, 1.0F, 0.02F, YAW);
            PredictionResult climbing = climb(f, back, SLOW_START_X + back.x);
            assertTrue(exact(climbing), describe(climbing));
            f.input(FORWARD);
            Vec3 forward = ClientMoveInput.of(f, FORWARD, 1.0F, 0.02F, YAW);
            PredictionResult off = stepOff(
                    f, back, forward, SLOW_START_X + back.x, (SLOW_START_X + back.x) * AIR_FRICTION + forward.x);
            assertTrue(exact(off), describe(off));
        }
    }

    @Test
    void theTickOffAClimbableDoesNotStartFromTheClampAfterAnOpposingInputThatEndsInsideIt() throws Exception {
        try (JavaTickFixture f = onAVine(SLOW_START_X, BACKWARD)) {
            Vec3 back = ClientMoveInput.of(f, BACKWARD, 1.0F, 0.02F, YAW);
            climb(f, back, SLOW_START_X + back.x);
            f.input(FORWARD);
            Vec3 forward = ClientMoveInput.of(f, FORWARD, 1.0F, 0.02F, YAW);
            PredictionResult off =
                    stepOff(f, back, forward, SLOW_START_X + back.x, CLIMB_CLAMP * AIR_FRICTION + forward.x);
            assertFalse(exact(off), describe(off));
        }
    }

    /** Stands in a vine one block above the floor, moving along +X at {@link #START_X} and holding forward. */
    private static JavaTickFixture onAVine() throws Exception {
        return onAVine(START_X, FORWARD);
    }

    /** Stands in a vine one block above the floor, moving along +X at {@code startX} and holding {@code keys}. */
    private static JavaTickFixture onAVine(double startX, ServerboundPlayerInput keys) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 1, 0), BlockIds.VINE.defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.xRot = YAW;
        f.player.yRot = 0.0F;
        f.place(new Vec3(0.9, FLOOR_Y + 1, 0.5), new Vec3(startX, 0.0, 0.0));
        f.input(keys);
        return f;
    }

    /** The climbing tick, moving {@code climbX} along +X. */
    private static PredictionResult climb(JavaTickFixture f, Vec3 input, double climbX) {
        return f.tick(new Vec3(0.9 + climbX, FLOOR_Y + 1, 0.5 + input.z), false);
    }

    /** The tick off the vine after a climbing tick of {@code climbX}, moving {@code offX} along +X. */
    private static PredictionResult stepOff(JavaTickFixture f, Vec3 input, double climbX, double offX) {
        return stepOff(f, input, input, climbX, offX);
    }

    /**
     * The tick off the vine with {@code offInput} after a climbing tick of {@code climbX} with {@code climbInput},
     * moving {@code offX} along +X.
     */
    private static PredictionResult stepOff(
            JavaTickFixture f, Vec3 climbInput, Vec3 offInput, double climbX, double offX) {
        double offZ = climbInput.z * AIR_FRICTION + offInput.z;
        return f.tick(new Vec3(0.9 + climbX + offX, FLOOR_Y + 1 + FALL, 0.5 + climbInput.z + offZ), false);
    }
}
