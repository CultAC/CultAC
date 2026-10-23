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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A player spamming jump on a slime floor under a ceiling two blocks above it (issue #92). Every jump hits the ceiling
 * at +0.2, and the landings leave small slime bounces: +0.0737, -0.0061 at the apex, then a landing of -0.0676 that
 * Entity#move clips short of the -0.0844 the player falls.
 */
class SlimeHeadHitterTest {
    private static final double GRAVITY = 0.08D;
    private static final double DRAG = 0.98F;
    private static final double CEILING = FLOOR_Y + 2;
    private static final float SPRINT_SPEED = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
    // Jump held (J) or released (_) each tick, as a player spamming the key.
    private static final String JUMPS = "JjJ_J_JJ__J_J_JJJ_JJ_J_JJ__JJ_J_J_JJ_J__JJJ_J_J";

    /**
     * At the apex, a starting velocity whose Y the engine zeroed falls -0.0784 into the floor the probe found, while
     * the client moves -0.0061. That move ends short of the floor, so it is not Entity#move's verticalCollisionBelow
     * and must not bounce the carried velocity to +0.0784: the next landing of -0.0676 flagged Simulation with a
     * predicted Y of zero.
     */
    @Test
    void anApexShortOfTheFloorDoesNotBounce26Dot1() throws Exception {
        run(ClientVersion.V_26_1, false, false, 0.0F);
    }

    @Test
    void sprintStrafing26Dot1() throws Exception {
        run(ClientVersion.V_26_1, true, true, 8.5F);
    }

    @Test
    void sprintStrafing26Dot3() throws Exception {
        run(ClientVersion.V_26_3, true, true, 8.5F);
    }

    private static void run(ClientVersion version, boolean sprint, boolean strafe, float yaw) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            slimeUnderCeiling(f);
            Client client = new Client(f, version, sprint, yaw);
            f.place(new Vec3(client.x, client.y, client.z), new Vec3(0, client.vy, 0));
            f.player.xRot = yaw;
            if (sprint) {
                var command = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
                new PacketEntityAction()
                        .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
            }
            List<String> failures = new ArrayList<>();
            for (int tick = 0; tick < JUMPS.length(); tick++) {
                var keys = new ServerboundPlayerInput(
                        true, false, false, strafe, JUMPS.charAt(tick) != '_', false, sprint);
                f.input(keys);
                Vec3 move = client.tick(keys);
                PredictionResult result = f.tick(new Vec3(client.x, client.y, client.z), client.onGround);
                if (!exact(result)) failures.add("tick " + tick + " move " + move + ": " + describe(result));
            }
            assertTrue(failures.isEmpty(), String.join("\n", failures));
        }
    }

    /** The vanilla client's tick between the slime floor and the ceiling, with no horizontal collision. */
    private static final class Client {
        final JavaTickFixture f;
        final ClientVersion version;
        final boolean sprint;
        final float yaw;
        double x = 0.5, y = FLOOR_Y, z = -14.5, vx, vy = -0.0784000015258789, vz;
        boolean onGround = true;
        int noJumpDelay;

        Client(JavaTickFixture f, ClientVersion version, boolean sprint, float yaw) {
            this.f = f;
            this.version = version;
            this.sprint = sprint;
            this.yaw = yaw;
        }

        /** One tick; the move it sends. */
        Vec3 tick(ServerboundPlayerInput keys) {
            // LivingEntity#aiStep
            if (noJumpDelay > 0) noJumpDelay--;
            if (Math.abs(vx) < 0.003) vx = 0;
            if (Math.abs(vy) < 0.003) vy = 0;
            if (Math.abs(vz) < 0.003) vz = 0;
            if (!keys.jump()) {
                noJumpDelay = 0;
            } else if (onGround && noJumpDelay == 0) {
                // LivingEntity#jumpFromGround
                vy = Math.max(0.42F, vy);
                if (sprint) {
                    float radians = yaw * 0.017453292F;
                    vx += -f.player.trigHandler.sin(radians) * 0.2;
                    vz += f.player.trigHandler.cos(radians) * 0.2;
                }
                noJumpDelay = 10;
            }
            // LivingEntity#travelInAir, with slime's 0.8F friction under a grounded player
            float friction = onGround ? 0.8F : 1.0F;
            float speed = onGround
                    ? (sprint ? SPRINT_SPEED : 0.1F) * (0.21600002F / (friction * friction * friction))
                    : sprint ? 0.025999999F : 0.02F;
            Vec3 input = ClientMoveInput.of(f, keys, 1.0F, speed, yaw);
            vx += input.x;
            vz += input.z;
            // Entity#move
            double moveY = vy;
            boolean below = false;
            if (y + moveY < FLOOR_Y) {
                moveY = FLOOR_Y - y;
                below = true;
            } else if (y + (double) 1.8F + moveY > CEILING) {
                moveY = CEILING - (y + (double) 1.8F);
            }
            Vec3 move = new Vec3(vx, moveY, vz);
            x += vx;
            y += moveY;
            z += vz;
            onGround = below;
            if (moveY != vy) {
                // A landing reads the slime; the ceiling's collision reads the air under the feet.
                if (version.isNewerThanOrEquals(ClientVersion.V_26_2)) {
                    // Entity#restituteMovementAfterCollisions
                    if (below && -vy >= GRAVITY) {
                        double portion = moveY / vy;
                        vy = (portion * GRAVITY - vy) * (1.0 + portion * (DRAG - 1.0));
                    } else {
                        vy = 0;
                    }
                } else {
                    // SlimeBlock#updateEntityMovementAfterFallOn, Block#updateEntityMovementAfterFallOn
                    vy = below ? -vy : 0;
                }
            }
            float drag = friction * 0.91F;
            vx *= drag;
            vy = (vy - GRAVITY) * DRAG;
            vz *= drag;
            // Entity#applyEffectsFromBlocks: SlimeBlock#stepOn
            if (onGround && Math.abs(vy) < 0.1) {
                double scale = 0.4 + Math.abs(vy) * 0.2;
                vx *= scale;
                vz *= scale;
            }
            return move;
        }
    }

    private static void slimeUnderCeiling(JavaTickFixture f) {
        for (int x = -16; x <= 31; x++) {
            for (int z = -16; z <= 31; z++) {
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(x, FLOOR_Y - 1, z), BlockIds.SLIME_BLOCK.defaultState());
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y + 2, z), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }
}
