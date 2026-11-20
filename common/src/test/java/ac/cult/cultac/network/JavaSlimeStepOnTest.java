package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
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
 * A 26.1 client sliding over a slime floor. Entity#move bounces the landing velocity, LivingEntity#travelInAir applies
 * gravity and the slime's 0.8F friction, and LivingEntity#aiStep then runs SlimeBlock#stepOn, which scales both
 * horizontal axes by 0.4 + |y| * 0.2 below 0.1 vertical speed. The next tick moves the client by that velocity; a sprint
 * jump adds the full LivingEntity#jumpFromGround push to it.
 */
class JavaSlimeStepOnTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput SPRINT_JUMP =
            new ServerboundPlayerInput(true, false, false, false, true, false, true);
    private static final Vec3 SLID = new Vec3(0.3, 0.0, -0.2);
    /** The landing's -0.0784 bounced to 0.0784 by SlimeBlock#bounceUp, then (0.0784 - 0.08) * 0.98F. */
    private static final double STEP_ON =
            0.4 + Math.abs((-((0.0 - 0.08) * (double) 0.98F) - 0.08) * (double) 0.98F) * 0.2;

    @Test
    void theNextTickMovesByTheStepOnScaledVelocity() throws Exception {
        assertTrue(slidesOn(STEP_ON), "stepOn scale");
        assertFalse(slidesOn(1.0), "no stepOn");
    }

    @Test
    void aSprintJumpAddsTheFullPushToTheStepOnScaledVelocity() throws Exception {
        assertTrue(sprintJumps(STEP_ON, STEP_ON), "stepOn scale");
        assertFalse(sprintJumps(1.0, 1.0), "no stepOn");
        assertFalse(sprintJumps(0.0, 0.0), "the push alone");
        assertFalse(sprintJumps(1.0, 0.0), "each axis scaled on its own");
    }

    private static boolean slidesOn(double scale) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 slid = slide(f);
            Vec3 carried = carried(scale, scale);
            return exact(f.tick(slid.add(carried), true));
        }
    }

    private static boolean sprintJumps(double scaleX, double scaleZ) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 slid = slide(f);
            f.input(SPRINT_JUMP);
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction()
                    .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
            // Yaw 0: the push is (0, 0.2F), and the sprinting W input on slime is 0.98F * 0.13F * 0.216F / 0.8F^3.
            float slime = 0.8F;
            double input = (double) 0.98F * (double) (0.13F * (0.21600002F / (slime * slime * slime)));
            Vec3 moved = carried(scaleX, scaleZ).add(0.0, (double) 0.42F, (double) 0.2F + input);
            return exact(f.tick(slid.add(moved), false));
        }
    }

    /** Places the client on the slime with {@link #SLID} and returns where the first tick's slide ends. */
    private static Vec3 slide(JavaTickFixture f) throws Exception {
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_1);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(x, FLOOR_Y - 1, z), BlockIds.SLIME_BLOCK.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        Vec3 start = new Vec3(0.5, FLOOR_Y, 0.5);
        f.place(start, new Vec3(SLID.x, (0.0 - 0.08) * (double) 0.98F, SLID.z));
        f.player.xRot = 0.0F;
        f.player.yRot = 0.0F;
        f.input(NONE);
        Vec3 slid = start.add(SLID);
        PredictionResult first = f.tick(slid, true);
        assertTrue(exact(first), describe(first));
        return slid;
    }

    /** The slide after the slime's 0.8F * 0.91F friction, scaled per axis. */
    private static Vec3 carried(double scaleX, double scaleZ) {
        double friction = (double) (0.8F * 0.91F);
        return new Vec3(SLID.x * friction * scaleX, 0.0, SLID.z * friction * scaleZ);
    }
}
