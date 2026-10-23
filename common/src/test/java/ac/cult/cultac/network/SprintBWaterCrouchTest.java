package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.sprint.SprintB;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * 1.21.4 LocalPlayer#aiStep crouches a player whose previous keys held shift unless it is flying, swimming or riding.
 * Crouching stops sprinting, and only a player whose eye was under water (LocalPlayer#isUnderWater) starts it again.
 */
class SprintBWaterCrouchTest {
    private static final ServerboundPlayerInput FORWARD_SNEAK =
            new ServerboundPlayerInput(true, false, false, false, false, true, false);
    private static final ServerboundPlayerInput FORWARD_SNEAK_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, true, true);
    private static final float YAW = 30.0F;

    /** A sprint-swimmer holding shift is not crouching, so it keeps sprinting. */
    @Test
    void aSwimmerHoldingShiftKeepsSprinting() throws Exception {
        try (var f = pool(3)) {
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            Vec3 velocity = Vec3.ZERO;
            f.place(position, velocity);
            f.input(FORWARD_SNEAK_SPRINT);
            startSprinting(f);
            for (int tick = 0; tick < 4; tick++) {
                Vec3 delta = velocity.add(ClientMoveInput.of(f, FORWARD_SNEAK_SPRINT, 1.0F, 0.02F, YAW));
                position = position.add(delta.x, 0.0, delta.z);
                PredictionResult result = f.tick(position, true);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
                // LivingEntity#travelInWater's sprinting slowDown.
                velocity = new Vec3(delta.x * 0.9F, 0.0, delta.z * 0.9F);
            }
            assertEquals(0.0, sprintB(f).violations, 0.0);
        }
    }

    /** A crouching player whose eye is under water starts sprinting with the sprint key. */
    @Test
    void aCrouchingPlayerUnderWaterStartsSprinting() throws Exception {
        try (var f = pool(3)) {
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, Vec3.ZERO);
            f.input(FORWARD_SNEAK);
            for (int tick = 0; tick < 3; tick++) f.tick(position, true);
            f.input(FORWARD_SNEAK_SPRINT);
            startSprinting(f);
            f.tick(position, true);
            assertEquals(0.0, sprintB(f).violations, 0.0);
        }
    }

    /** A crouching wader whose eye is above the water stops sprinting. */
    @Test
    void aCrouchingWaderWithItsEyeAboveTheWaterSprintingFlags() throws Exception {
        try (var f = pool(1)) {
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, Vec3.ZERO);
            f.input(FORWARD_SNEAK_SPRINT);
            startSprinting(f);
            for (int tick = 0; tick < 4; tick++) f.tick(position, true);
            assertTrue(sprintB(f).violations > 0, "SprintB violations " + sprintB(f).violations);
        }
    }

    /** A 1.21.4 client in water {@code depth} blocks deep over the floor. */
    private static JavaTickFixture pool(int depth) throws Exception {
        var f = new JavaTickFixture();
        setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = FLOOR_Y; y < FLOOR_Y + depth; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.WATER.defaultState());
                }
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.xRot = YAW;
        f.player.yRot = 20.0F;
        return f;
    }

    private static void startSprinting(JavaTickFixture f) {
        var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
        new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
    }

    private static SprintB sprintB(JavaTickFixture f) {
        return f.player.checkManager.getCheck(SprintB.class);
    }
}
