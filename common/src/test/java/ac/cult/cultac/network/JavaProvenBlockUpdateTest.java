package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Server block updates around the player, applied at the ping proving them. ClientPacketListener handles the update in
 * Minecraft#runAllTasks between client ticks, so every tick before the pong moves in the old world and every tick after
 * it in the new one; the engine keeps no swim or pose state open across the pong.
 */
class JavaProvenBlockUpdateTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final int SURFACE_Y = FLOOR_Y + 2;
    private static final int DEEP_SURFACE_Y = FLOOR_Y + 5;

    /**
     * A sprint-swimmer on the floor of a deep pool the server drains. Entity#updateSwimming keeps the swim while the
     * client still has the water, and ends it in the first tick after the pong, when the sprinter is dry. That tick's
     * aiStep still sees last tick's swimming pose out of water, so LocalPlayer#isMovingSlowly crawls the input, and
     * Player#updatePlayerPose stands the player up at its end.
     */
    @Test
    void aDrainedPoolEndsTheSwimAtTheProvingPong() throws Exception {
        try (var f = pool()) {
            float yaw = 0.0F;
            f.player.xRot = yaw;
            f.player.yRot = 0.0F;
            Vec3[] state = {new Vec3(0.5, FLOOR_Y, -6.0), Vec3.ZERO};
            f.place(state[0], Vec3.ZERO);
            assertExact(f.tick(state[0], true), "standing");

            command(f, PlayerCommandAction.START_SPRINTING);
            f.input(FORWARD_SPRINT);
            for (int tick = 1; tick <= 3; tick++) {
                waterTick(f, state, yaw, "swim tick " + tick);
                assertEquals(tick > 1, f.player.swimState.swimming(), "swim tick " + tick);
            }
            assertEquals(Pose.SWIMMING, f.player.pose);

            int drain = f.player.lastTransactionSent.incrementAndGet();
            setPool(f, BlockIds.AIR.defaultState(), drain);
            for (int tick = 0; tick < 2; tick++) {
                String where = "held pong tick " + tick;
                waterTick(f, state, yaw, where);
                assertTrue(f.player.swimState.swimming(), where + ": the client still has the water");
                assertEquals(Pose.SWIMMING, f.player.pose, where);
            }

            f.pongThrough(drain);
            assertFalse(
                    f.player.compensatedWorld.hasRecentClientWaterChanges(poolBox()),
                    "the proven drain left the water timing open");
            for (int tick = 0; tick < 3; tick++) {
                String where = "dry tick " + tick;
                // The SWIMMING pose out of water is Entity#isVisuallyCrawling.
                groundTick(f, state, yaw, tick == 0 ? CRAWLING : 1.0F, where);
                assertFalse(f.player.swimState.swimming(), where + ": the swim outlived the water");
                assertEquals(Pose.STANDING, f.player.pose, where);
            }
        }
    }

    /**
     * A walker crawling in a one-block tunnel whose ceiling the server removes. Player#updatePlayerPose keeps the crawl
     * while the client still has the ceiling, and its aiStep still crawls the first tick after the pong, which starts in
     * last tick's pose; the pose stands up at that tick's end and the next tick walks at full speed.
     */
    @Test
    void aRemovedCeilingStandsTheCrawlerUpAtTheProvingPong() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x, FLOOR_Y + 1, z), BlockIds.STONE.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            float yaw = 0.0F;
            f.player.xRot = yaw;
            f.player.yRot = 0.0F;
            Vec3[] state = {new Vec3(0.5, FLOOR_Y, -6.0), Vec3.ZERO};
            f.place(state[0], Vec3.ZERO);
            // Neither the standing nor the crouching box fits under the ceiling.
            f.player.refreshPlayerPose();
            assertEquals(Pose.SWIMMING, f.player.pose);
            f.input(FORWARD);
            for (int tick = 0; tick < 2; tick++) {
                walkTick(f, state, yaw, CRAWLING, "crawl tick " + tick);
                assertEquals(Pose.SWIMMING, f.player.pose, "crawl tick " + tick);
            }

            int removal = f.player.lastTransactionSent.incrementAndGet();
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    f.player.compensatedWorld.queueServerBlockUpdate(
                            x, FLOOR_Y + 1, z, BlockIds.AIR.defaultState(), removal, true);
                }
            }
            for (int tick = 0; tick < 2; tick++) {
                String where = "held pong tick " + tick;
                walkTick(f, state, yaw, CRAWLING, where);
                assertEquals(Pose.SWIMMING, f.player.pose, where + ": the client still has the ceiling");
            }

            f.pongThrough(removal);
            walkTick(f, state, yaw, CRAWLING, "first open tick");
            assertEquals(Pose.STANDING, f.player.pose, "first open tick");
            for (int tick = 0; tick < 2; tick++) {
                String where = "standing tick " + tick;
                walkTick(f, state, yaw, 1.0F, where);
                assertEquals(Pose.STANDING, f.player.pose, where);
            }
        }
    }

    /**
     * A sprint-swimmer rising through deep water at an upward look, whose pool an update behind an earlier ping
     * drains, so the client's water stays open after that ping's pong. The client is dry on the next tick: its
     * Entity#updateSwimming ends the swim and Player#travel adds no look pull, so the movement itself proves the swim
     * over, and the committed swim and the pose follow it rather than the optimistic water.
     */
    @Test
    void aMovementWithoutTheSwimPullCommitsTheSwimOverWhileTheWaterIsOpen() throws Exception {
        try (var f = pool(DEEP_SURFACE_Y)) {
            float yaw = 0.0F;
            float pitch = -30.0F;
            f.player.xRot = yaw;
            f.player.yRot = pitch;
            // Entity#calculateViewVector's y.
            double lookY = -f.player.trigHandler.sin(pitch * ((float) Math.PI / 180F));
            Vec3 position = new Vec3(0.5, FLOOR_Y + 1.0, -6.0);
            f.place(position, Vec3.ZERO);
            f.input(FORWARD);

            // Not sprinting: LivingEntity#travelInWater's 0.8 slowDown, then getFluidFallingAdjustedMovement.
            Vec3 delta = ClientMoveInput.of(f, FORWARD, 1.0F, 0.02F, yaw);
            position = position.add(delta);
            assertExact(f.tick(position, false), "first tick");
            Vec3 velocity = new Vec3(delta.x * 0.8F, -0.005, delta.z * 0.8F);

            command(f, PlayerCommandAction.START_SPRINTING);
            f.input(FORWARD_SPRINT);
            for (int tick = 1; tick <= 3; tick++) {
                // Entity#updateSwimming reads the sprint flag the previous tick's aiStep set.
                double y = swimPitch(f, velocity.y, lookY, position, tick > 1);
                Vec3 input = ClientMoveInput.of(f, FORWARD_SPRINT, 1.0F, 0.02F, yaw);
                delta = new Vec3(velocity.x + input.x, y, velocity.z + input.z);
                position = position.add(delta);
                assertExact(f.tick(position, false), "swim tick " + tick);
                assertEquals(tick > 1, f.player.swimState.swimming(), "swim tick " + tick);
                // The sprinting 0.9 slowDown; a sprinter has no fluid gravity.
                velocity = new Vec3(delta.x * 0.9F, y * 0.8F, delta.z * 0.9F);
            }
            assertEquals(Pose.SWIMMING, f.player.pose);

            int earlier = f.player.lastTransactionSent.incrementAndGet();
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    for (int y = FLOOR_Y; y <= DEEP_SURFACE_Y; y++) {
                        f.player.compensatedWorld.queueServerBlockUpdate(
                                x, y, z, BlockIds.AIR.defaultState(), earlier, false);
                    }
                }
            }
            f.pongThrough(earlier);
            assertTrue(f.player.compensatedWorld.hasRecentClientWaterChanges(poolBox(DEEP_SURFACE_Y)));

            for (int tick = 0; tick < 2; tick++) {
                String where = "dry tick " + tick;
                // LivingEntity#travelInAir: Player#getFlyingSpeed reads the sprint flag. The first dry tick still
                // starts in the swimming pose, which out of water is Entity#isVisuallyCrawling.
                double y = Math.abs(velocity.y) < 0.003 ? 0.0 : velocity.y;
                Vec3 input = ClientMoveInput.of(f, FORWARD_SPRINT, tick == 0 ? CRAWLING : 1.0F, 0.025999999F, yaw);
                delta = new Vec3(velocity.x + input.x, y, velocity.z + input.z);
                position = position.add(delta);
                assertExact(f.tick(position, false), where);
                assertFalse(f.player.swimState.swimming(), where + ": the committed swim outlived the water");
                assertEquals(Pose.STANDING, f.player.pose, where);
                velocity = new Vec3(delta.x * 0.91F, (y - 0.08) * 0.98F, delta.z * 0.91F);
            }
        }
    }

    /**
     * LivingEntity#aiStep's 0.003 cutoff, then Player#travel's swim pitch for a swimmer; the head is in water whenever
     * the look is level or down.
     */
    private static double swimPitch(JavaTickFixture f, double y, double lookY, Vec3 position, boolean swimming) {
        if (Math.abs(y) < 0.003) y = 0.0;
        if (swimming
                && (lookY <= 0.0
                        || f.player.compensatedWorld.getWaterFluidLevelAt(position.x, position.y + 0.9, position.z)
                                > 0)) {
            return y + (lookY - y) * (lookY < -0.2 ? 0.085 : 0.06);
        }
        return y;
    }

    /** A ping written before the update only bounds it from below, so the fluid timing it changes stays open. */
    @Test
    void anUpdateBehindAnEarlierPingKeepsTheWaterTimingOpen() throws Exception {
        try (var f = pool()) {
            int earlier = f.player.lastTransactionSent.incrementAndGet();
            f.player.compensatedWorld.queueServerBlockUpdate(
                    0, FLOOR_Y, 0, BlockIds.AIR.defaultState(), earlier, false);
            f.pongThrough(earlier);
            assertTrue(f.player.compensatedWorld.hasRecentClientWaterChanges(poolBox()));
        }
    }

    // LivingEntity#travelInWater on the floor at a level look: speed 0.02F and the sprinting 0.9F slowDown.
    private static void waterTick(JavaTickFixture f, Vec3[] state, float yaw, String where) {
        Vec3 delta = state[1].add(ClientMoveInput.of(f, FORWARD_SPRINT, 1.0F, 0.02F, yaw));
        state[0] = state[0].add(delta.x, 0.0, delta.z);
        assertExact(f.tick(state[0], true), where);
        state[1] = new Vec3(delta.x * 0.9F, 0.0, delta.z * 0.9F);
    }

    // Entity#isVisuallyCrawling: LocalPlayer#isMovingSlowly scales the keys by Attributes#SNEAKING_SPEED.
    private static final float CRAWLING = 0.3F;

    // LivingEntity#travelInAir on stone: the walking getSpeed() and 0.6F * 0.91F friction.
    private static void walkTick(JavaTickFixture f, Vec3[] state, float yaw, float movingSlowly, String where) {
        Vec3 delta = state[1].add(ClientMoveInput.of(f, FORWARD, movingSlowly, 0.1F, yaw));
        state[0] = state[0].add(delta.x, 0.0, delta.z);
        assertExact(f.tick(state[0], true), where);
        double friction = 0.6F * 0.91F;
        state[1] = new Vec3(delta.x * friction, 0.0, delta.z * friction);
    }

    // LivingEntity#travelInAir on stone: the sprinting getSpeed() and 0.6F * 0.91F friction.
    private static void groundTick(JavaTickFixture f, Vec3[] state, float yaw, float movingSlowly, String where) {
        float sprintSpeed = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
        Vec3 delta = state[1].add(ClientMoveInput.of(f, FORWARD_SPRINT, movingSlowly, sprintSpeed, yaw));
        state[0] = state[0].add(delta.x, 0.0, delta.z);
        assertExact(f.tick(state[0], true), where);
        double friction = 0.6F * 0.91F;
        state[1] = new Vec3(delta.x * friction, 0.0, delta.z * friction);
    }

    private static void assertExact(PredictionResult result, String where) {
        assertTrue(JavaTickFixture.exact(result), where + ": " + describe(result));
    }

    private static JavaTickFixture pool() throws Exception {
        return pool(SURFACE_Y);
    }

    private static JavaTickFixture pool(int surfaceY) throws Exception {
        var f = new JavaTickFixture();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = FLOOR_Y; y <= surfaceY; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.WATER.defaultState());
                }
            }
        }
        // The fixture's own pool is written outside a proof; let its window close.
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        return f;
    }

    /** The server's block updates for every pool block, each proven by {@code transaction}. */
    private static void setPool(JavaTickFixture f, int state, int transaction) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = FLOOR_Y; y <= SURFACE_Y; y++) {
                    f.player.compensatedWorld.queueServerBlockUpdate(x, y, z, state, transaction, true);
                }
            }
        }
    }

    private static SimpleCollisionBox poolBox() {
        return poolBox(SURFACE_Y);
    }

    private static SimpleCollisionBox poolBox(int surfaceY) {
        return new SimpleCollisionBox(-8, FLOOR_Y, -8, 9, surfaceY + 1, 9);
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action) {
        var command = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        new PacketEntityAction()
                .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
    }
}
