package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
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
 * A 26.1 client settling on a slime floor after a bounce, then sprinting forward. Each tick Entity#move stops the
 * descending velocity at the floor, SlimeBlock#bounceUp reverses it, LivingEntity#travelInAir applies gravity, and
 * SlimeBlock#stepOn scales the horizontal velocity by 0.4 + |y| * 0.2. The vertical velocity keeps alternating about
 * 0.0784 / 1.98 without reaching LivingEntity#aiStep's 0.003 threshold, so the client stays on the ground; while it
 * stands still LocalPlayer#sendPosition sends no position, only the tick end.
 */
class JavaSlimeSettledWalkTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    /** The last bounce's rise in the capture, from the floor. */
    private static final double RISE = 0.033382;

    private static final float SLIME_FRICTION = 0.8F;
    /** The sprinting forward input on slime at yaw 0: 0.98F * 0.13F * 0.21600002F / 0.8F^3. */
    private static final double INPUT =
            (double) 0.98F * (double) (0.13F * (0.21600002F / (SLIME_FRICTION * SLIME_FRICTION * SLIME_FRICTION)));

    @Test
    void walkingAtOnceAfterSettlingIsExact() throws Exception {
        walkAfterIdleTicks(0);
    }

    @Test
    void walkingAfterAnUnsentTickIsExact() throws Exception {
        walkAfterIdleTicks(1);
    }

    @Test
    void walkingAfterStandingStillIsExact() throws Exception {
        walkAfterIdleTicks(13);
    }

    private static void walkAfterIdleTicks(int idle) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_1);
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x, FLOOR_Y - 1, z), BlockIds.SLIME_BLOCK.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, new Vec3(0.0, RISE, 0.0));
            f.player.xRot = 0.0F;
            f.player.yRot = 0.0F;
            f.input(NONE);

            PredictionResult rise = f.tick(position.add(0.0, RISE, 0.0), false);
            assertTrue(exact(rise), "rise: " + describe(rise));
            double velocityY = (RISE - 0.08) * (double) 0.98F;
            PredictionResult land = f.tick(position, true);
            assertTrue(exact(land), "land: " + describe(land));
            velocityY = settle(velocityY);
            for (int tick = 0; tick < idle; tick++) {
                f.tickEnd();
                velocityY = settle(velocityY);
            }

            f.input(FORWARD_SPRINT);
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction()
                    .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
            double carried = 0.0;
            for (int tick = 0; tick < 6; tick++) {
                double move = carried + INPUT;
                position = position.add(0.0, 0.0, move);
                velocityY = settle(velocityY);
                carried = move * (double) (SLIME_FRICTION * 0.91F) * (0.4 + Math.abs(velocityY) * 0.2);
                PredictionResult result = f.tick(position, true);
                assertTrue(exact(result), "idle " + idle + " walk tick " + tick + ": " + describe(result));
            }
        }
    }

    /** The vertical velocity after one more grounded tick: bounced off the floor, then travel's gravity and drag. */
    private static double settle(double velocityY) {
        assertTrue(velocityY < -0.003, "LivingEntity#aiStep keeps " + velocityY);
        return (-velocityY - 0.08) * (double) 0.98F;
    }
}
