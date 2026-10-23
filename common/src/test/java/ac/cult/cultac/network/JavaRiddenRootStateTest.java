package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.SprintingState;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ridden-root state the client keeps for its own vehicle: input shaping, camel poses, root yaw and sprint flag. */
class JavaRiddenRootStateTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_LEFT =
            new ServerboundPlayerInput(true, false, true, false, false, false, false);
    private static final int ROOT = 100;
    private static final long GAME_TIME = 1000L;
    // LivingEntity#getFrictionInfluencedSpeed on stone (friction 0.6F).
    private static final float STONE_SPEED_FACTOR = 0.21600002F / (0.6F * 0.6F * 0.6F);

    @Test
    void oneTwentyOneFourHorseDiagonalNormalizesTheUnshapedInput() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
            mount(f, EntityTypeIds.HORSE, new Vec3(0.5, FLOOR_Y, 0.5));
            f.input(FORWARD_LEFT);
            assertExact(f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5), true));

            // 1.21.4 xxa/zza are the raw impulses times 0.98F; AbstractHorse#getRiddenInput gives (0.49, 0, 0.98),
            // which LivingEntity#getInputVector normalizes before scaling by the ridden speed.
            Vec3 move = new Vec3(0.49F, 0.0, 0.98F).normalize().scale(0.225F * STONE_SPEED_FACTOR);
            assertExact(f.rideTick(new Vec3(0.5 + move.x, FLOOR_Y, 0.5 + move.z), true));
        }
    }

    @Test
    void oneTwentyOneFourHorseDiagonalIsNotSquareShaped() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
            mount(f, EntityTypeIds.HORSE, new Vec3(0.5, FLOOR_Y, 0.5));
            f.input(FORWARD_LEFT);
            f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5), true);

            // The 1.21.5+ shaping: (0.70710677, 0.70710677) halved on X, not normalized.
            double speed = 0.225F * STONE_SPEED_FACTOR;
            Vec3 move = new Vec3(0.70710677F * 0.5F, 0.0, 0.70710677F).scale(speed);
            assertRejected(f.rideTick(new Vec3(0.5 + move.x, FLOOR_Y, 0.5 + move.z), true), "VehicleOffset");
        }
    }

    @Test
    void aStandingUpCamelRefusesInputUntilItsTransitionEnds() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntityCamel camel = camel(f, GAME_TIME - 50);
            f.input(FORWARD);
            assertExact(f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5), true));

            // Pose time 51 of Camel#STANDUP_DURATION_TICKS 52: Camel#getRiddenInput is still zero.
            assertExact(f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5), true));
            assertEquals(GAME_TIME - 50, camel.lastPoseChangeTick);

            // Pose time 52 ends the transition: the camel walks at its 0.09 speed.
            double walk = (double) 0.98F * (double) (0.09F * STONE_SPEED_FACTOR);
            assertExact(f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5 + walk), true));
        }
    }

    @Test
    void aStandingUpCamelCannotWalk() throws Exception {
        try (var f = new JavaTickFixture()) {
            camel(f, GAME_TIME - 10);
            f.input(FORWARD);
            f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5), true);

            double walk = (double) 0.98F * (double) (0.09F * STONE_SPEED_FACTOR);
            assertRejected(f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5 + walk), true), "VehicleOffset");
        }
    }

    @Test
    void aSeatedCamelStandsUpOnForwardInputAndThenRefusesToMove() throws Exception {
        try (var f = new JavaTickFixture()) {
            // Sat down 40 ticks ago: Camel#isInPoseTransition is over, so forward input stands it up.
            PacketEntityCamel camel = camel(f, -(GAME_TIME - 40));
            f.input(FORWARD);
            assertExact(f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5), true));
            assertTrue(camel.isCamelSitting());

            // Camel#tickRidden sees zza > 0 and calls Camel#standUp at this tick's game time.
            assertExact(f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5), true));
            assertFalse(camel.isCamelSitting());
            assertEquals(GAME_TIME + 1, camel.lastPoseChangeTick);

            double walk = (double) 0.98F * (double) (0.09F * STONE_SPEED_FACTOR);
            assertRejected(f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5 + walk), true), "VehicleOffset");
        }
    }

    @Test
    void aRefusingGroundedCamelDropsItsHorizontalCarry() throws Exception {
        try (var f = new JavaTickFixture()) {
            camel(f, GAME_TIME - 10);
            f.input(NONE);
            f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5), true);

            // Camel#travel zeroes deltaMovement X/Z while it refuses to move on the ground.
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0, 0, 0.1));
            assertExact(f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5), true));
        }
    }

    @Test
    void aSprintingCamelGetsItsBonusWhileStillDashingAfterItsCooldown() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntityCamel camel = camel(f, GAME_TIME - 100);
            f.input(FORWARD);
            f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5), true);

            // A long dash keeps DASH until Camel#tick sees it land; Camel#getRiddenSpeed only reads the cooldown.
            camel.dashing = true;
            camel.dashCooldown = 0;
            f.player.vehicleData.camelSprintingState = SprintingState.STARTED;
            double sprint = (double) 0.98F * (double) ((0.09F + 0.1F) * STONE_SPEED_FACTOR);
            assertExact(f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5 + sprint), true));
        }
    }

    @Test
    void aHappyGhastTravelsAlongTheRootYawItsMoveVehicleCarries() throws Exception {
        try (var f = new JavaTickFixture()) {
            double y = FLOOR_Y + 5;
            PacketEntity root = f.player.compensatedEntities.getEntity(spawn(f, EntityTypeIds.HAPPY_GHAST, y));
            ((PacketEntityHappyGhast) root).hasBodyArmor = true;
            ride(f);
            f.input(FORWARD);
            assertExact(f.rideTick(new Vec3(0.5, y, 0.5), false));

            // HappyGhast#tickRidden turned the root from 0 to 7.2 degrees toward a 90 degree controller;
            // the MoveVehicle carries that 7.2. Smoothing it again would travel along 0.576 degrees.
            f.player.xRot = 7.2F;
            // HappyGhast#getRiddenInput's 3.9F * flying_speed forward input, moved at flying_speed * 5 / 3.
            double thrust = (double) (3.9F * 0.05F) * (double) (0.05F * 5.0F / 3.0F);
            float yaw = 7.2F * ((float) Math.PI / 180F);
            Vec3 move = new Vec3(-thrust * CultMath.sin(yaw), 0, thrust * CultMath.cos(yaw));
            assertExact(f.rideTick(new Vec3(0.5 + move.x, y, 0.5 + move.z), false));
        }
    }

    @Test
    void aSprintFlaggedRootSkipsFluidGravity() throws Exception {
        try (var f = new JavaTickFixture()) {
            double y = FLOOR_Y + 1;
            skeletonHorseInDeepWater(f, y, true);

            // LivingEntity#getFluidFallingAdjustedMovement reads the root's sprint flag: no gravity / 16.
            assertExact(f.rideTick(new Vec3(0.5, y, 0.5), false));
        }
    }

    @Test
    void anUnflaggedRootSinksByFluidGravity() throws Exception {
        try (var f = new JavaTickFixture()) {
            double y = FLOOR_Y + 1;
            skeletonHorseInDeepWater(f, y, false);

            // The plain root moves by the carried 0 * 0.8F less 0.08 / 16.
            assertExact(f.rideTick(new Vec3(0.5, y - 0.005, 0.5), false));
        }
    }

    @Test
    void aSprintFlaggedSkeletonHorseSlowsByTheSprintWaterSlowDown() throws Exception {
        try (var f = new JavaTickFixture()) {
            flood(f, FLOOR_Y, FLOOR_Y);
            mount(f, EntityTypeIds.SKELETON_HORSE, new Vec3(0.5, FLOOR_Y, 0.5));
            sprintFlag(f);
            f.input(FORWARD);
            assertExact(f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5), true));

            // LivingEntity#travelInWater: moveRelative(0.02F), then 0.9F over SkeletonHorse#getWaterSlowDown.
            double accelerated = (double) 0.98F * (double) 0.02F;
            // The horse moves with the keys of the tick before.
            f.input(NONE);
            assertExact(f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5 + accelerated), true));
            assertExact(f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5 + accelerated + accelerated * 0.9F), true));
        }
    }

    @Test
    void aSubThresholdCarryIsDroppedBeforeTheRiddenInput() throws Exception {
        try (var f = new JavaTickFixture()) {
            // Facing +X: Mth.sin(-90 degrees) is -1 and Mth.cos is 0, so the forward input is all X.
            f.player.xRot = -90.0F;
            mount(f, EntityTypeIds.HORSE, new Vec3(0.5, FLOOR_Y, 0.5));
            // The root ticks before LocalPlayer#rideTick, so this input reaches the horse a tick later.
            f.input(FORWARD);
            assertExact(f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5), true));

            // LivingEntity#aiStep zeroes the 0.002 carry before travelRidden adds the controller input.
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0.002, 0, 0));
            float zza = 0.98F;
            // LocalPlayer#modifyInputSpeedForSquareMovement on a single axis.
            float length = (float) Math.sqrt(zza * zza);
            zza = zza * (1.0F / length) * Math.min(length * (float) Math.sqrt(1.0F), 1.0F);
            double input = (double) zza * (0.225F * STONE_SPEED_FACTOR);
            assertExact(f.rideTick(new Vec3(0.5 + input, FLOOR_Y, 0.5), true));
        }
    }

    @Test
    void everyDashValueAfterTheFirstTickRestartsAnIdleCooldown() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.addEntity(ROOT, EntityTypeIds.CAMEL, new Vec3(0.5, FLOOR_Y, 0.5), 0, 0, 0);
            PacketEntityCamel camel = (PacketEntityCamel) f.player.compensatedEntities.getEntity(ROOT);
            int dash = WatchableIndexUtil.forModel(ProtocolVersion.V26_3).camelDash();

            // Spawn metadata precedes the camel's first tick, which Entity#firstTick exempts.
            dash(f, dash);
            assertEquals(0, camel.dashCooldown);

            f.tickEnd();
            // Camel#onSyncedDataUpdated restarts an idle cooldown even for an unchanged value.
            dash(f, dash);
            assertEquals(55, camel.dashCooldown);
        }
    }

    private static void dash(JavaTickFixture f, int index) {
        f.player.compensatedEntities.updateEntityMetadata(
                ROOT, List.of(new EntityMetadata.Entry(index, false, ByteBuffer.allocate(0))));
    }

    /** A saddled camel on the stone floor at game time {@link #GAME_TIME}, synced LAST_POSE_CHANGE_TICK. */
    private static PacketEntityCamel camel(JavaTickFixture f, long lastPoseChangeTick) {
        f.player.compensatedWorld.applyClientTime(new WorldPackets.TimeUpdate(GAME_TIME, Map.of()));
        PacketEntityCamel camel = (PacketEntityCamel) mount(f, EntityTypeIds.CAMEL, new Vec3(0.5, FLOOR_Y, 0.5));
        int index = WatchableIndexUtil.forModel(ProtocolVersion.V26_3).camelLastPoseChangeTick();
        f.player.compensatedEntities.updateEntityMetadata(
                ROOT, List.of(new EntityMetadata.Entry(index, lastPoseChangeTick, ByteBuffer.allocate(0))));
        assertEquals(lastPoseChangeTick, camel.lastPoseChangeTick);
        return camel;
    }

    /** A sprint-flagged or plain skeleton horse floating at {@code y} in water, after one stationary tick. */
    private static void skeletonHorseInDeepWater(JavaTickFixture f, double y, boolean sprinting) {
        flood(f, FLOOR_Y, FLOOR_Y + 4);
        mount(f, EntityTypeIds.SKELETON_HORSE, new Vec3(0.5, y, 0.5));
        if (sprinting) sprintFlag(f);
        f.input(NONE);
        assertExact(f.rideTick(new Vec3(0.5, y, 0.5), false));
    }

    private static void sprintFlag(JavaTickFixture f) {
        f.player.compensatedEntities.updateEntityMetadata(
                ROOT, List.of(EntityMetadata.Entry.byteValue(WatchableIndexUtil.ENTITY_SHARED_FLAGS, (byte) 0x08)));
    }

    private static void flood(JavaTickFixture f, int fromY, int toY) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 3; z++) {
                for (int y = fromY; y <= toY; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.WATER.defaultState());
                }
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /** A saddled horse-family root at {@code at}, mounted by the player through set_passengers. */
    private static PacketEntity mount(JavaTickFixture f, int type, Vec3 at) {
        f.player.compensatedEntities.addEntity(ROOT, type, at, 0, 0, 0);
        PacketEntity root = f.player.compensatedEntities.getEntity(ROOT);
        ((PacketEntityHorse) root).hasSaddle = true;
        ride(f);
        return root;
    }

    private static int spawn(JavaTickFixture f, int type, double y) {
        f.player.compensatedEntities.addEntity(ROOT, type, new Vec3(0.5, y, 0.5), 0, 0, 0);
        return ROOT;
    }

    private static void ride(JavaTickFixture f) {
        var packet = new ClientboundSetPassengers(ROOT, List.of(f.player.entityID));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        f.player.packetEntityReplication.onSetPassengers(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** No flag, and the accepted movement is the target within the 1e-5 double-precision bound. */
    private static void assertExact(PredictionResult result) {
        double error = result.getTarget().distanceTo(result.getAcceptedClosestToTarget());
        assertTrue(
                exact(result) && error < 1.0E-5,
                describe(result) + " target " + result.getTarget() + " accepted "
                        + result.getAcceptedClosestToTarget());
    }
}
