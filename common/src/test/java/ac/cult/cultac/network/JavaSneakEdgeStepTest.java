package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import org.junit.jupiter.api.Test;

/**
 * A client sprinting onto the edge of a one-block stair step, holding shift for three ticks so
 * Player#maybeBackOffFromEdge holds it on the step while deltaMovement keeps the crouched input, then releasing shift
 * still crouched (SuperDebug paste of issue #80). The step is the fixture floor with its blocks from x = 1 removed.
 */
class JavaSneakEdgeStepTest {
    private static final ServerboundPlayerInput SPRINT_SNEAK =
            new ServerboundPlayerInput(true, false, false, false, false, true, true);
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    @Test
    void theStepHidesTheCrouchedInputUntilShiftIsReleased() throws Exception {
        try (var f = new JavaTickFixture()) {
            sprintSneakAndRelease(f);
        }
    }

    /** Entity#getInputVector rotates the keys by Mth#sin and Mth#cos, whose one table a glide does not change. */
    @Test
    void aGlideLeavesTheInputTheStepHides() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.isGliding = true;
            f.player.refreshPlayerPose();
            float yaw = 30.0F, pitch = 10.0F;
            rotate(f, yaw, pitch);
            Vec3 position = new Vec3(0.5, FLOOR_Y + 20.0, 0.5);
            Vec3 velocity = new Vec3(0.4, -0.5, 1.0);
            f.place(position, velocity);
            for (int tick = 0; tick < 3; tick++) {
                // LivingEntity#travelFallFlying stores the glide as deltaMovement and moves by it.
                velocity = ElytraTransform.getElytraMovement(
                        f.player, velocity, ReachUtils.getLook(f.player, yaw, pitch), pitch, 0.08);
                position = position.add(velocity);
                PredictionResult result = f.tick(position, false);
                assertTrue(result.getSimulationContext().usesFallFlyingMovement(), describe(result));
                assertTrue(exact(result), describe(result));
            }
            f.player.isGliding = false;
            f.player.refreshPlayerPose();

            sprintSneakAndRelease(f);
        }
    }

    private static void sprintSneakAndRelease(JavaTickFixture f) {
        for (int x = 1; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), BlockIds.AIR.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        // LivingEntity#travelInAir's ground friction, 0.6F * 0.91F, applied to the previous move.
        double friction = 0.6F * 0.91F;
        f.place(
                recorded(198.94016874383942, 71.0, 149.62823627614756),
                new Vec3(0.2680494490970773 * friction, 0.0, -0.014168255770954374 * friction));
        var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
        new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);

        f.input(SPRINT_SNEAK);
        assertExact(f, 627.3058F, 36.90929F, 199.2137826447959, 149.61450538947219);
        assertExact(f, 627.5539F, 37.05812F, 199.25136092438385, 149.60537454586728);
        assertExact(f, 627.802F, 37.15734F, 199.29197044227644, 149.59892008337192);

        f.input(SPRINT);
        assertExact(f, 628.39746F, 37.30617F, 199.4069482786223, 149.59432611141338);
    }

    private static void assertExact(JavaTickFixture f, float yaw, float pitch, double x, double z) {
        rotate(f, yaw, pitch);
        PredictionResult result = f.tick(recorded(x, 71.0, z), true);
        assertTrue(exact(result), describe(result));
    }

    private static void rotate(JavaTickFixture f, float yaw, float pitch) {
        f.player.xRot = yaw;
        f.player.yRot = pitch;
    }

    /** A recorded position moved onto the fixture: its step block (198, 70, 149) becomes (0, FLOOR_Y - 1, 0). */
    private static Vec3 recorded(double x, double y, double z) {
        return new Vec3(x - 198.0, y - 71.0 + FLOOR_Y, z - 149.0);
    }
}
