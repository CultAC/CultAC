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
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A client in deep water starts sprinting while its eye is under the surface. Entity#updateSwimming swims it on the next
 * tick, before the server's swimming flag can arrive, and LocalPlayer#shouldStopSwimSprinting keeps the sprint while it
 * stays in water, even once it rises until its standing eye height would clear the surface. Player#updatePlayerPose
 * gives it the swimming box at the end of that tick.
 */
class JavaSprintSwimStartTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final byte SPRINTING = 0x08;
    private static final byte SWIMMING = 0x10;

    @Test
    void startingAndStoppingToSwimInAnyDirectionIsExact() throws Exception {
        for (float pitch : new float[] {-30.0F, 0.0F, 30.0F}) {
            for (float yaw : new float[] {0.0F, 90.0F, -135.0F}) {
                swim(yaw, pitch);
            }
        }
    }

    private static void swim(float yaw, float pitch) throws Exception {
        try (var f = pool(FLOOR_Y + 2)) {
            SprintG sprintG = sprintG(f);
            f.player.xRot = yaw;
            f.player.yRot = pitch;
            // Entity#calculateViewVector's y.
            double lookY = -f.player.trigHandler.sin(pitch * ((float) Math.PI / 180F));
            Vec3 position = new Vec3(0.5, FLOOR_Y + 1.2, 0.5);
            f.place(position, Vec3.ZERO);
            f.input(FORWARD);

            // Not sprinting: LivingEntity#travelInWater's 0.8 slowDown, then getFluidFallingAdjustedMovement.
            Vec3 delta = ClientMoveInput.of(f, FORWARD, 1.0F, 0.02F, yaw);
            position = position.add(delta);
            assertExact(f.tick(position, false), "first tick");
            Vec3 velocity = new Vec3(delta.x * 0.8F, -0.005, delta.z * 0.8F);

            command(f, PlayerCommandAction.START_SPRINTING);
            f.input(FORWARD_SPRINT);
            boolean eyeCleared = false;
            for (int tick = 1; tick < 10; tick++) {
                // Entity#updateSwimming reads the sprint flag the previous tick's aiStep set.
                double y = swimPitch(f, velocity.y, lookY, position, tick > 1);
                Vec3 input = ClientMoveInput.of(f, FORWARD_SPRINT, 1.0F, 0.02F, yaw);
                delta = new Vec3(velocity.x + input.x, y, velocity.z + input.z);
                position = position.add(delta);
                PredictionResult result = f.tick(position, false);
                // EntityFluidInteraction#update: the standing eye over the surface block's 8/9 water.
                eyeCleared |= position.y + 1.62F > FLOOR_Y + 2 + 8.0F / 9.0F;
                String where = "yaw " + yaw + " pitch " + pitch + " sprint tick " + tick;
                assertExact(result, where);
                assertEquals(0, sprintG.violations, where + ": " + describe(result));
                assertEquals(tick > 1 ? Pose.SWIMMING : Pose.STANDING, f.player.pose, where);
                // The sprinting 0.9 slowDown; a sprinter has no fluid gravity.
                velocity = new Vec3(delta.x * 0.9F, y * 0.8F, delta.z * 0.9F);
            }
            if (pitch < 0) assertTrue(eyeCleared, "the standing eye never cleared the surface");

            command(f, PlayerCommandAction.STOP_SPRINTING);
            f.input(FORWARD);
            for (int tick = 0; tick < 4; tick++) {
                double y = swimPitch(f, velocity.y, lookY, position, tick == 0);
                Vec3 input = ClientMoveInput.of(f, FORWARD, 1.0F, 0.02F, yaw);
                delta = new Vec3(velocity.x + input.x, y, velocity.z + input.z);
                position = position.add(delta);
                String where = "yaw " + yaw + " pitch " + pitch + " stop tick " + tick;
                assertExact(f.tick(position, false), where);
                assertEquals(tick == 0 ? Pose.SWIMMING : Pose.STANDING, f.player.pose, where);
                velocity = new Vec3(delta.x * 0.8F, fluidFalling(y <= 0.0, y * 0.8F), delta.z * 0.8F);
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

    /** LivingEntity#getFluidFallingAdjustedMovement for a non-sprinter. */
    private static double fluidFalling(boolean falling, double y) {
        double gravity = 0.08 / 16.0;
        if (falling && Math.abs(y - 0.005) >= 0.003 && Math.abs(y - gravity) < 0.003) return -0.003;
        return y - gravity;
    }

    /**
     * A sprinter on the floor of deep water swims the tick after its sprint starts and takes the 0.6 swimming box at
     * that tick's end, so it fits under a ceiling one block over the floor a round trip before the server's flag.
     */
    @Test
    void aSwimmerFitsUnderALowCeilingBeforeTheServerFlag() throws Exception {
        try (var f = pool(FLOOR_Y + 2)) {
            for (int x = -8; x <= 8; x++) {
                for (int z = 2; z <= 8; z++) {
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x, FLOOR_Y + 1, z), BlockIds.STONE.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            sprintG(f);
            float yaw = 0.0F;
            f.player.xRot = yaw;
            f.player.yRot = 0.0F;
            Vec3 position = new Vec3(0.5, FLOOR_Y, 1.0);
            f.place(position, Vec3.ZERO);
            assertExact(f.tick(position, true), "standing");

            command(f, PlayerCommandAction.START_SPRINTING);
            f.input(FORWARD_SPRINT);
            Vec3 velocity = Vec3.ZERO;
            for (int tick = 1; tick < 12; tick++) {
                // On the floor: Block#updateEntityMovementAfterFallOn zeroes y, and the level look keeps it zero.
                Vec3 delta = velocity.add(ClientMoveInput.of(f, FORWARD_SPRINT, 1.0F, 0.02F, yaw));
                position = position.add(delta.x, 0.0, delta.z);
                String where = "sprint tick " + tick;
                assertExact(f.tick(position, true), where);
                assertEquals(tick > 1 ? Pose.SWIMMING : Pose.STANDING, f.player.pose, where);
                velocity = new Vec3(delta.x * 0.9F, 0.0, delta.z * 0.9F);
            }
            assertTrue(position.z + 0.3 > 2.0, "the swimmer never reached the low ceiling: " + position.z);
        }
    }

    /**
     * ClientPacketListener#handleSetEntityData sets the client's swimming flag between ticks. Entity#updateSwimming keeps
     * it only while the sprinter is in water, so a stale flag on dry ground is cleared before Player#updatePlayerPose,
     * one in shallow water latches the swim, and a swimmer that leaves the water cannot latch again on its return.
     */
    @Test
    void theServersSwimFlagLatchesOnlyInWater() throws Exception {
        try (var f = new JavaTickFixture()) {
            SprintG sprintG = sprintG(f);
            float yaw = 0.0F;
            f.player.xRot = yaw;
            f.player.yRot = 0.0F;
            f.place(new Vec3(0.5, FLOOR_Y, -7.0), Vec3.ZERO);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.input(FORWARD_SPRINT);
            Vec3[] state = {new Vec3(0.5, FLOOR_Y, -7.0), Vec3.ZERO};

            groundTick(f, state, yaw);
            sharedFlags(f, (byte) (SPRINTING | SWIMMING));
            assertTrue(f.player.swimState.swimming());
            assertEquals(Pose.STANDING, f.player.pose, "the shared flags changed the pose before the tick ended");
            groundTick(f, state, yaw);
            assertFalse(f.player.swimState.swimming(), "a stale flag outlived a dry tick");
            assertEquals(Pose.STANDING, f.player.pose);

            water(f, true);
            sharedFlags(f, (byte) (SPRINTING | SWIMMING));
            for (int tick = 0; tick < 2; tick++) {
                waterTick(f, state, yaw);
                assertTrue(f.player.swimState.swimming(), "the latched swim ended in water");
                assertEquals(Pose.SWIMMING, f.player.pose);
            }
            assertEquals(0, sprintG.violations, "a latched swimmer is SprintG");

            water(f, false);
            groundTick(f, state, yaw);
            assertFalse(f.player.swimState.swimming(), "the swim outlived leaving the water");
            assertEquals(Pose.STANDING, f.player.pose);

            water(f, true);
            for (int tick = 0; tick < 2; tick++) {
                waterTick(f, state, yaw);
                assertFalse(f.player.swimState.swimming(), "a wader with its eye over the water swam");
            }
            assertTrue(sprintG.violations > 0, "a sprinting wader is not SprintG");
        }
    }

    // LivingEntity#travelInAir on stone: the sprinting getSpeed() and 0.6F * 0.91F friction.
    private static void groundTick(JavaTickFixture f, Vec3[] state, float yaw) {
        float sprintSpeed = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
        Vec3 delta = state[1].add(ClientMoveInput.of(f, FORWARD_SPRINT, 1.0F, sprintSpeed, yaw));
        state[0] = state[0].add(delta.x, 0.0, delta.z);
        assertExact(f.tick(state[0], true), "ground tick");
        double friction = 0.6F * 0.91F;
        state[1] = new Vec3(delta.x * friction, 0.0, delta.z * friction);
    }

    // LivingEntity#travelInWater on the floor: speed 0.02F and the sprinting 0.9F slowDown.
    private static void waterTick(JavaTickFixture f, Vec3[] state, float yaw) {
        Vec3 delta = state[1].add(ClientMoveInput.of(f, FORWARD_SPRINT, 1.0F, 0.02F, yaw));
        state[0] = state[0].add(delta.x, 0.0, delta.z);
        assertExact(f.tick(state[0], true), "water tick");
        state[1] = new Vec3(delta.x * 0.9F, 0.0, delta.z * 0.9F);
    }

    /** One block of water never covers a standing eye, so Entity#updateSwimming cannot swim the wader. */
    @Test
    void sprintingThroughShallowWaterIsSprintG() throws Exception {
        try (var f = pool(FLOOR_Y)) {
            SprintG sprintG = sprintG(f);
            float yaw = 0.0F;
            f.player.xRot = yaw;
            f.player.yRot = 0.0F;
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, Vec3.ZERO);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.input(FORWARD_SPRINT);
            Vec3 velocity = Vec3.ZERO;
            for (int tick = 0; tick < 4; tick++) {
                Vec3 delta = velocity.add(ClientMoveInput.of(f, FORWARD_SPRINT, 1.0F, 0.02F, yaw));
                position = position.add(delta.x, 0.0, delta.z);
                f.tick(position, true);
                velocity = new Vec3(delta.x * 0.9F, 0.0, delta.z * 0.9F);
            }
            assertTrue(sprintG.violations > 0, "a sprinting wader is not SprintG");
        }
    }

    private static void assertExact(PredictionResult result, String where) {
        assertTrue(result.getOffset() < 1.0E-5, where + ": " + describe(result));
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
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        return f;
    }

    private static void water(JavaTickFixture f, boolean water) {
        int state = water ? BlockIds.WATER.defaultState() : BlockIds.AIR.defaultState();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), state);
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static SprintG sprintG(JavaTickFixture f) {
        f.player.setExperimentalChecks(true);
        SprintG sprintG = f.player.checkManager.getCheck(SprintG.class);
        sprintG.violations = 0;
        return sprintG;
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action) {
        var command = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
    }

    /** Self entity data carrying the shared flags, applied by its trailing proof between client ticks. */
    private static void sharedFlags(JavaTickFixture f, byte flags) {
        var packet = new EntityMetadata(
                f.player.entityID,
                List.of(new EntityMetadata.Entry(
                        WatchableIndexUtil.ENTITY_SHARED_FLAGS, flags, ByteBuffer.allocate(0))));
        var event = new PacketSendEvent<>(f.player.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        new PacketSelfMetadataListener().onSetEntityData(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }
}
