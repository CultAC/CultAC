package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.sprint.SprintG;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.ClientMiningState;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A player stands in one block of water with a second water block two blocks over its feet and jumps. Its standing eye
 * reaches the upper block's water before its feet leave the lower one, and from 26.1 EntityFluidInteraction#update
 * puts the eye in water from the bottom of the block it is in up to the fluid's height. LocalPlayer#isUnderWater then
 * ends LocalPlayer#isInShallowWater, and the held sprint key starts a sprint on that tick.
 */
class JavaSprintStackedWaterTest {
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final ServerboundPlayerInput FORWARD_JUMP_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, true, false, true);
    private static final float YAW = 180.0F;
    /** A 26.1.2 client's captured feet heights while LivingEntity#jumpInLiquid lifts it out of the lower block. */
    private static final double[] CAPTURED_Y = {
        62.0,
        62.03499999910593,
        62.09799999791384,
        62.183399996817116,
        62.28671999606371,
        62.40437599579859,
        62.53350079609499,
        62.67180063697733
    };

    /**
     * The eye at the sixth jump tick's start, 62.40437599579859 + 1.62 in the capture, is 0.0244 into the upper block,
     * whose water fills it to 8/9, so the seventh tick is under water and starts the sprint. The fixture's floor puts
     * the capture's feet at 62 on its floor at 64.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_4", "V_1_21_11", "V_26_1", "V_26_2", "V_26_3"})
    void theSprintStartedWithTheEyeInTheUpperWaterIsNotSprintG(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            water(f, FLOOR_Y);
            water(f, FLOOR_Y + 2);
            SprintG sprintG = sprintG(f);
            f.player.xRot = YAW;
            f.player.yRot = 0.0F;
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.9);
            f.place(position, Vec3.ZERO);
            f.input(FORWARD_SPRINT);

            // LivingEntity#travelInWater on the floor, then getFluidFallingAdjustedMovement for a non-sprinter.
            Vec3 delta = ClientMoveInput.of(f, FORWARD_SPRINT, 1.0F, 0.02F, YAW);
            position = position.add(delta.x, 0.0, delta.z);
            assertExact(f.tick(position, true), version + " standing");
            Vec3 velocity = new Vec3(delta.x * 0.8F, -0.005, delta.z * 0.8F);

            f.input(FORWARD_JUMP_SPRINT);
            for (int tick = 0; tick < CAPTURED_Y.length - 1; tick++) {
                boolean sprinting = tick == CAPTURED_Y.length - 2;
                if (sprinting) command(f, PlayerCommandAction.START_SPRINTING);
                Vec3 input = ClientMoveInput.of(f, FORWARD_JUMP_SPRINT, 1.0F, 0.02F, YAW);
                delta = new Vec3(velocity.x + input.x, velocity.y + 0.04F, velocity.z + input.z);
                assertEquals(CAPTURED_Y[tick + 1] - CAPTURED_Y[tick], delta.y, 1.0E-12, "captured jump " + tick);
                position = position.add(delta);
                String where = version + " jump " + tick;
                PredictionResult result = f.tick(position, false);
                assertExact(result, where);
                assertEquals(
                        sprinting, result.getSimulationContext().getWorldData().isUnderwater(), where);
                assertEquals(0.0, sprintG.violations, where + ": " + describe(result));
                float slowDown = sprinting ? 0.9F : 0.8F;
                velocity = new Vec3(delta.x * slowDown, delta.y * 0.8F - (sprinting ? 0.0 : 0.005), delta.z * slowDown);
            }
        }
    }

    /** 26.1 and 26.2 EntityFluidInteraction#update hold the eye to FluidState#getHeight under a solid face too. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_26_1", "V_26_2"})
    void anEyeUnderASolidCeilingStaysUnderTheFluidHeight(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 1, 0), BlockIds.STONE.defaultState());
            water(f, FLOOR_Y);
            var body = new SimpleCollisionBox(0.2, FLOOR_Y, 0.2, 0.8, FLOOR_Y + 1.8, 0.8, false);
            var feet = new Vec3(0.5, FLOOR_Y, 0.5);
            assertTrue(ClientFluidQueries.eyesInWater(f.player, body, feet, FLOOR_Y + 8 / 9.0F), version.toString());
            assertFalse(ClientFluidQueries.eyesInWater(f.player, body, feet, FLOOR_Y + 0.95), version.toString());
        }
    }

    /**
     * From 1.20.5 Entity#updateFluidOnEyes, and from 26.1 EntityFluidInteraction#update, sample the eye itself: an eye
     * over the 8/9 surface of a water source is dry, for movement and for Player#getDestroySpeed alike.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_20_5", "V_1_21", "V_1_21_2", "V_1_21_4", "V_1_21_11", "V_26_1", "V_26_2", "V_26_3"})
    void anEyeOverTheSurfaceIsDry(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            water(f, FLOOR_Y + 1);
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            assertEquals(false, eyeInWaterAt(f, FLOOR_Y + 1.95), version + " over the surface");
            assertEquals(true, eyeInWaterAt(f, FLOOR_Y + 1.88), version + " under the surface");
        }
    }

    /** Before 1.20.5 Entity#updateFluidOnEyes samples 0.11111111F under the eye, which reaches under the surface. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_20_3"})
    void anEyeBefore1_20_5SamplesUnderItself(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            water(f, FLOOR_Y + 1);
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            assertEquals(true, eyeInWaterAt(f, FLOOR_Y + 1.95), version + " over the surface");
            assertEquals(false, eyeInWaterAt(f, FLOOR_Y + 2.02), version + " over the lowered surface");
        }
    }

    /** Both eye samplers for a standing player whose eye is at {@code eyeY}; they must agree. */
    private static boolean eyeInWaterAt(JavaTickFixture f, double eyeY) {
        double feetY = eyeY - f.player.getEyeHeight();
        f.place(new Vec3(0.5, feetY, 0.5), Vec3.ZERO);
        var body = new SimpleCollisionBox(0.2, feetY, 0.2, 0.8, feetY + 1.8, 0.8, false);
        boolean movement = ClientFluidQueries.eyesInWater(f.player, body, new Vec3(0.5, feetY, 0.5), eyeY);
        assertEquals(movement, ClientMiningState.of(f.player).eyesInWater(), "mining eye at " + eyeY);
        return movement;
    }

    private static void water(JavaTickFixture f, int y) {
        f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), BlockIds.WATER.defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void assertExact(PredictionResult result, String where) {
        assertTrue(result.getOffset() < 1.0E-5, where + ": " + describe(result));
    }

    private static SprintG sprintG(JavaTickFixture f) {
        f.player.setExperimentalChecks(true);
        SprintG sprintG = f.player.checkManager.getCheck(SprintG.class);
        sprintG.violations = 0;
        return sprintG;
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action) {
        var command = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        new PacketEntityAction()
                .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
    }
}
