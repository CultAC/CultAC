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
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockSpeedFactors;
import ac.cult.cultac.utils.nmsutil.Friction;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JavaTravelAttributeTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput SNEAK_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, true, false);
    private static final ServerboundPlayerInput SPRINT_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final double SOUL_SAND_TOP = FLOOR_Y - 0.125;
    // LivingEntity#travel: moveRelative(0.1F, 0.98F forward input), then the 0.6F * 0.91F ground friction.
    private static final double WALK = (double) 0.98F * (double) 0.1F;
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    // The same move at getSpeed() with the minecraft:sprinting modifier.
    private static final double SPRINT = (double) 0.98F * (double) 0.13F;
    // JavaTickFixture's floor ends at x = 9; the player's box overhangs it by 0.27.
    private static final double EDGE_X = 9.27;
    // Sneaking hides 0.047 of edge velocity at sneaking_speed 0.3 and 0.156 at 1.0, on top of the sneaking input.
    private static final double EDGE_SNEAK = 0.2;
    // LivingEntity#travelRidden: the 0.98F forward input at a horse's getRiddenSpeed, its movement_speed 0.225.
    private static final double RIDDEN_WALK = (double) 0.98F * (double) 0.225F;

    @Test
    void soulSpeedCarriesFullSpeedOnSoulSand() throws Exception {
        try (var f = onSoulSand()) {
            attribute(f, "minecraft:movement_efficiency", 0.0, 1.0);

            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, SOUL_SAND_TOP, 1.5), true));
            // LivingEntity#getBlockSpeedFactor: Mth.lerp(movement_efficiency, 0.4F, 1.0F).
            assertExact(f.tick(new Vec3(0.5, SOUL_SAND_TOP, 1.5 + GROUND_FRICTION), true));
        }
    }

    @Test
    void partialMovementEfficiencyStillSlowsOnSoulSand() throws Exception {
        try (var f = onSoulSand()) {
            attribute(f, "minecraft:movement_efficiency", 0.0, 0.5);

            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, SOUL_SAND_TOP, 1.5), true));
            PredictionResult full = f.tick(new Vec3(0.5, SOUL_SAND_TOP, 1.5 + GROUND_FRICTION), true);
            assertFalse(exact(full), describe(full));
        }
    }

    @Test
    void soulSpeedWaitsForItsAttributeProof() throws Exception {
        try (var f = onSoulSand()) {
            int proof = sendAttribute(f, "minecraft:movement_efficiency", 0.0, 1.0);

            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, SOUL_SAND_TOP, 1.5), true));
            f.pongThrough(proof);
            assertExact(f.tick(new Vec3(0.5, SOUL_SAND_TOP, 1.5 + 0.4F * GROUND_FRICTION), true));
        }
    }

    @Test
    void waterMovementEfficiencyWalksAtLandSpeedInWater() throws Exception {
        try (var f = inWater()) {
            attribute(f, "minecraft:water_movement_efficiency", 0.0, 1.0);

            f.input(FORWARD);
            // LivingEntity#travelInWater: speed 0.02F lerps to getSpeed(), slowDown 0.8F to 0.54600006F.
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5 + WALK), true));
            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5 + WALK + WALK * 0.54600006F), true));
        }
    }

    @Test
    void partialWaterMovementEfficiencyDoesNotReachLandSpeed() throws Exception {
        try (var f = inWater()) {
            attribute(f, "minecraft:water_movement_efficiency", 0.0, 0.5);

            f.input(FORWARD);
            PredictionResult full = f.tick(new Vec3(0.5, FLOOR_Y, 0.5 + WALK), true);
            assertFalse(exact(full), describe(full));
        }
    }

    @Test
    void sprintingWithWaterMovementEfficiencySwimsAtSprintSpeed() throws Exception {
        try (var f = inWater()) {
            attribute(f, "minecraft:water_movement_efficiency", 0.0, 1.0);
            f.player.isSprinting = true;
            f.player.sprintState.onClientTick(true);

            f.input(SPRINT_FORWARD);
            // LivingEntity#travelInWater lerps the 0.02F fluid speed to getSpeed(), which carries the sprint modifier.
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5 + SPRINT), true));
        }
    }

    @Test
    void walkingWithWaterMovementEfficiencyDoesNotReachSprintSpeed() throws Exception {
        try (var f = inWater()) {
            attribute(f, "minecraft:water_movement_efficiency", 0.0, 1.0);

            f.input(FORWARD);
            PredictionResult sprint = f.tick(new Vec3(0.5, FLOOR_Y, 0.5 + SPRINT), true);
            assertFalse(exact(sprint), describe(sprint));
        }
    }

    @Test
    void respawnWithoutKeptModifiersRestoresTheWaterMovementEfficiencyBase() throws Exception {
        try (var f = inWater()) {
            attribute(f, "minecraft:water_movement_efficiency", 1.0, -0.75);
            // ClientPacketListener#handleRespawn: AttributeMap#assignBaseValues copies only the base.
            respawnInWater(f, (byte) 0);

            f.input(FORWARD);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5 + WALK), true));
        }
    }

    @Test
    void respawnWithoutKeptModifiersDropsWaterMovementEfficiency() throws Exception {
        try (var f = inWater()) {
            attribute(f, "minecraft:water_movement_efficiency", 0.0, 1.0);
            respawnInWater(f, (byte) 0);

            f.input(FORWARD);
            PredictionResult full = f.tick(new Vec3(0.5, FLOOR_Y, 0.5 + WALK), true);
            assertRejected(full, "Speed");
        }
    }

    @Test
    void respawnKeepingModifiersKeepsWaterMovementEfficiency() throws Exception {
        try (var f = inWater()) {
            attribute(f, "minecraft:water_movement_efficiency", 0.0, 1.0);
            respawnInWater(f, ClientboundRespawn.KEEP_ATTRIBUTE_MODIFIERS);

            f.input(FORWARD);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5 + WALK), true));
        }
    }

    @Test
    void respawnWithoutKeptModifiersDropsMovementEfficiency() throws Exception {
        try (var f = onSoulSand()) {
            attribute(f, "minecraft:movement_efficiency", 0.0, 1.0);
            respawn(f, (byte) 0);
            standOnSoulSand(f);

            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, SOUL_SAND_TOP, 1.5), true));
            PredictionResult full = f.tick(new Vec3(0.5, SOUL_SAND_TOP, 1.5 + GROUND_FRICTION), true);
            assertRejected(full, "Speed");
        }
    }

    @Test
    void negativeGravityLiftsThePlayer() throws Exception {
        try (var f = new JavaTickFixture()) {
            attribute(f, "minecraft:gravity", 0.08, -0.12);
            Vec3 start = new Vec3(0.5, FLOOR_Y + 5, 0.5);
            f.place(start, Vec3.ZERO);

            f.input(NONE);
            assertExact(f.tick(start, false));
            // LivingEntity#travelInAir: (0 - gravity) * 0.98F.
            assertExact(f.tick(start.add(0, 0.04 * 0.98F, 0), false));
        }
    }

    @Test
    void negativeGravityDoesNotLiftFasterThanItsValue() throws Exception {
        try (var f = new JavaTickFixture()) {
            attribute(f, "minecraft:gravity", 0.08, -0.12);
            Vec3 start = new Vec3(0.5, FLOOR_Y + 5, 0.5);
            f.place(start, Vec3.ZERO);

            f.input(NONE);
            assertExact(f.tick(start, false));
            PredictionResult faster = f.tick(start.add(0, 0.08 * 0.98F, 0), false);
            assertFalse(exact(faster), describe(faster));
        }
    }

    @Test
    void gravityAboveOneFallsAtOne() throws Exception {
        try (var f = new JavaTickFixture()) {
            attribute(f, "minecraft:gravity", 0.08, 1.92);
            Vec3 start = new Vec3(0.5, FLOOR_Y + 10, 0.5);
            f.place(start, Vec3.ZERO);

            f.input(NONE);
            assertExact(f.tick(start, false));
            // RangedAttribute("gravity", 0.08, -1.0, 1.0) clamps the client's value.
            assertExact(f.tick(start.add(0, -0.98F, 0), false));
        }
    }

    @Test
    void gravityAboveOneDoesNotFallFasterThanOne() throws Exception {
        try (var f = new JavaTickFixture()) {
            attribute(f, "minecraft:gravity", 0.08, 1.92);
            Vec3 start = new Vec3(0.5, FLOOR_Y + 10, 0.5);
            f.place(start, Vec3.ZERO);

            f.input(NONE);
            assertExact(f.tick(start, false));
            PredictionResult faster = f.tick(start.add(0, -2.0 * 0.98F, 0), false);
            assertFalse(exact(faster), describe(faster));
        }
    }

    @Test
    void sneakingSpeedAttributeSetsTheSneakingInput() throws Exception {
        try (var f = new JavaTickFixture()) {
            attribute(f, "minecraft:sneaking_speed", 0.3, 0.7);
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO);

            f.input(SNEAK);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5), true));
            f.input(SNEAK_FORWARD);
            // LocalPlayer#modifyInput: the 0.98F forward input times sneaking_speed 1.0.
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5 + WALK), true));
        }
    }

    @Test
    void sneakingFasterThanTheSneakingSpeedAttributeFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            attribute(f, "minecraft:sneaking_speed", 0.3, 0.45);
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO);

            f.input(SNEAK);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5), true));
            f.input(SNEAK_FORWARD);
            PredictionResult full = f.tick(new Vec3(0.5, FLOOR_Y, 0.5 + WALK), true);
            assertFalse(exact(full), describe(full));
        }
    }

    @Test
    void sneakingSpeedAttributeSetsTheEdgeSneakVelocity() throws Exception {
        try (var f = new JavaTickFixture()) {
            attribute(f, "minecraft:sneaking_speed", 0.3, 0.7);
            f.place(new Vec3(EDGE_X, FLOOR_Y, 0.5), new Vec3(0, -0.0784000015258789, 0.1));

            f.input(SNEAK);
            assertExact(f.tick(new Vec3(EDGE_X, FLOOR_Y, 0.6), true));
            assertExact(f.tick(new Vec3(EDGE_X, FLOOR_Y, 0.6 + 0.1 * GROUND_FRICTION + EDGE_SNEAK), true));
        }
    }

    @Test
    void edgeSneakVelocityBeyondTheSneakingSpeedAttributeFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(EDGE_X, FLOOR_Y, 0.5), new Vec3(0, -0.0784000015258789, 0.1));

            f.input(SNEAK);
            assertExact(f.tick(new Vec3(EDGE_X, FLOOR_Y, 0.6), true));
            PredictionResult hidden = f.tick(new Vec3(EDGE_X, FLOOR_Y, 0.6 + 0.1 * GROUND_FRICTION + EDGE_SNEAK), true);
            assertFalse(exact(hidden), describe(hidden));
        }
    }

    @Test
    void aVehiclesMovementEfficiencyLiftsItsSoulSandSlowdown() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity horse = horseOnSoulSand(f);
            f.player.compensatedEntities.updateAttributes(
                    100, List.of(snapshot("minecraft:movement_efficiency", 0.0, 1.0)));

            // LivingEntity#getBlockSpeedFactor lerps the ridden horse's own soul sand factor to 1.
            assertEquals(1.0F, BlockSpeedFactors.nextTickForResult(f.player, ridden(f, horse)));
        }
    }

    @Test
    void aVehiclesPartialMovementEfficiencyStillSlowsOnSoulSand() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity horse = horseOnSoulSand(f);
            assertEquals(0.4F, BlockSpeedFactors.nextTickForResult(f.player, ridden(f, horse)));

            f.player.compensatedEntities.updateAttributes(
                    100, List.of(snapshot("minecraft:movement_efficiency", 0.0, 0.5)));
            // Mth.lerp in float.
            float lerped = 0.4F + 0.5F * (1.0F - 0.4F);
            assertEquals(lerped, BlockSpeedFactors.nextTickForResult(f.player, ridden(f, horse)));
        }
    }

    @Test
    void aRiddenHorsesWaterMovementEfficiencyWalksAtItsSpeedInWater() throws Exception {
        try (var f = new JavaTickFixture()) {
            riddenHorseInWater(f, 1.0, true);

            // LivingEntity#travelInWater lerps 0.02F to the horse's getRiddenSpeed, its movement_speed 0.225.
            assertExact(rideForward(f, RIDDEN_WALK));
        }
    }

    @Test
    void aRiddenHorseInWaterGetsNoSprintModifier() throws Exception {
        try (var f = new JavaTickFixture()) {
            riddenHorseInWater(f, 1.0, true);

            PredictionResult sprint = rideForward(f, RIDDEN_WALK * 1.3);
            assertRejected(sprint, "VehicleOffset");
        }
    }

    @Test
    void aRiddenHorsesPartialWaterMovementEfficiencyDoesNotReachItsSpeed() throws Exception {
        try (var f = new JavaTickFixture()) {
            riddenHorseInWater(f, 0.5, true);

            PredictionResult full = rideForward(f, RIDDEN_WALK);
            assertRejected(full, "VehicleOffset");
        }
    }

    @Test
    void anAirborneRiddenHorseHalvesItsWaterMovementEfficiency() throws Exception {
        try (var f = new JavaTickFixture()) {
            riddenHorseInWater(f, 1.0, false);

            // travelInWater halves water_movement_efficiency while the horse is off the ground.
            float speed = 0.02F + (0.225F - 0.02F) * 0.5F;
            assertExact(rideForward(f, (double) 0.98F * (double) speed));
        }
    }

    @Test
    void anAirborneRiddenHorseDoesNotWalkAtItsSpeedInWater() throws Exception {
        try (var f = new JavaTickFixture()) {
            riddenHorseInWater(f, 1.0, false);

            PredictionResult full = rideForward(f, RIDDEN_WALK);
            assertRejected(full, "VehicleOffset");
        }
    }

    @Test
    void aRiddenSkeletonHorseLerpsItsWaterSlowDownWithItsWaterMovementEfficiency() throws Exception {
        for (float efficiency : new float[] {1.0F, 0.5F}) {
            try (var f = new JavaTickFixture()) {
                riddenInWater(f, EntityTypeIds.SKELETON_HORSE, efficiency, true);

                // LivingEntity#travelInWater lerps SkeletonHorse#getWaterSlowDown's .96F toward 0.54600006F.
                double accelerated = (double) 0.98F * (double) (0.02F + (0.2F - 0.02F) * efficiency);
                // The horse moves with the keys of the tick before.
                f.input(NONE);
                assertExact(rideForward(f, accelerated));
                float slowDown = 0.96F + (0.54600006F - 0.96F) * efficiency;
                assertExact(rideForward(f, accelerated + accelerated * slowDown));
            }
        }
    }

    @Test
    void anAirborneRiddenSkeletonHorseLerpsItsWaterSlowDownWithHalfItsEfficiency() throws Exception {
        try (var f = new JavaTickFixture()) {
            riddenInWater(f, EntityTypeIds.SKELETON_HORSE, 1.0, false);

            double accelerated = (double) 0.98F * (double) (0.02F + (0.2F - 0.02F) * 0.5F);
            f.input(NONE);
            assertExact(rideForward(f, accelerated));
            float slowDown = 0.96F + (0.54600006F - 0.96F) * 0.5F;
            assertExact(rideForward(f, accelerated + accelerated * slowDown));
        }
    }

    @Test
    void aRiddenSkeletonHorseDoesNotCarryItsUnliftedWaterSlowDown() throws Exception {
        try (var f = new JavaTickFixture()) {
            riddenInWater(f, EntityTypeIds.SKELETON_HORSE, 1.0, true);

            double accelerated = (double) 0.98F * (double) 0.2F;
            f.input(NONE);
            assertExact(rideForward(f, accelerated));
            assertRejected(rideForward(f, accelerated + accelerated * 0.96F), "VehicleOffset");
        }
    }

    @Test
    void waterMovementEfficiencyLerpsTheSlowDownInFloat() {
        for (float efficiency : new float[] {0.16666667F, 0.33333334F, 0.5F, 0.6666667F, 1.0F}) {
            for (double slowDown : new double[] {0.9, 0.8}) {
                // LivingEntity#travelInWater: slowDown += (0.54600006F - slowDown) * waterWalker
                float lerped = (float) slowDown;
                lerped += (0.54600006F - lerped) * efficiency;
                assertEquals(lerped, Friction.applyWaterMovementEfficiency(efficiency, slowDown));
            }
        }
    }

    /**
     * A saddled horse at (0.5, FLOOR_Y, 0.5) in water with its water_movement_efficiency, mounted by the player
     * through set_passengers, after one stationary ridden tick reporting {@code onGround}.
     */
    private static void riddenHorseInWater(JavaTickFixture f, double waterMovementEfficiency, boolean onGround) {
        riddenInWater(f, EntityTypeIds.HORSE, waterMovementEfficiency, onGround);
    }

    /** {@link #riddenHorseInWater} for a saddled horse of this type. */
    private static void riddenInWater(JavaTickFixture f, int type, double waterMovementEfficiency, boolean onGround) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 3; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), BlockIds.WATER.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.compensatedEntities.addEntity(100, type, new Vec3(0.5, FLOOR_Y, 0.5), 0, 0, 0);
        ((PacketEntityHorse) f.player.compensatedEntities.getEntity(100)).hasSaddle = true;
        var packet = new ClientboundSetPassengers(100, List.of(f.player.entityID));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        f.player.packetEntityReplication.onSetPassengers(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        f.pongThrough(sendAttribute(f, 100, "minecraft:water_movement_efficiency", 0.0, waterMovementEfficiency));
        // AbstractHorse#tick runs before LocalPlayer#rideTick hands it this tick's keys.
        f.input(FORWARD);
        f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5), onGround);
    }

    private static PredictionResult rideForward(JavaTickFixture f, double distance) {
        return f.rideTick(new Vec3(0.5, FLOOR_Y, 0.5 + distance), true);
    }

    private static PacketEntity horseOnSoulSand(JavaTickFixture f) {
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 1, 0), BlockIds.SOUL_SAND.defaultState());
        f.player.compensatedEntities.addEntity(100, EntityTypeIds.HORSE, new Vec3(0.5, SOUL_SAND_TOP, 0.5), 0, 0, 0);
        return f.player.compensatedEntities.getEntity(100);
    }

    private static PredictionResult ridden(JavaTickFixture f, PacketEntity vehicle) {
        Vec3 end = new Vec3(0.5, SOUL_SAND_TOP, 0.5);
        SimulationContext context = new SimulationContext(
                end,
                end,
                Vec3.ZERO,
                ClientVersion.V_26_3,
                null,
                f.player.compensatedEntities,
                vehicle,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                true,
                false,
                false,
                false,
                null,
                0.0F,
                0,
                DesyncStatus.FALSE,
                0,
                0,
                0,
                null,
                false,
                null,
                false,
                null,
                null,
                Pose.STANDING,
                1.0F);
        PredictionResult result = Mockito.mock(PredictionResult.class);
        Mockito.when(result.getSimulationContext()).thenReturn(context);
        return result;
    }

    private static JavaTickFixture onSoulSand() throws Exception {
        var f = new JavaTickFixture();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 4; z++) {
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(x, FLOOR_Y - 1, z), BlockIds.SOUL_SAND.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        standOnSoulSand(f);
        return f;
    }

    private static void standOnSoulSand(JavaTickFixture f) {
        f.place(new Vec3(0.5, SOUL_SAND_TOP, 0.5), new Vec3(0, -0.0784000015258789, 1.0));
        f.player.onGround = true;
        f.player.lastOnGround = true;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
    }

    private static JavaTickFixture inWater() throws Exception {
        var f = new JavaTickFixture();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 2; z++) {
                for (int y = FLOOR_Y; y <= FLOOR_Y + 2; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.WATER.defaultState());
                }
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO);
        return f;
    }

    /** A respawn with no following attribute packet, after which the client has loaded into the level. */
    private static void respawn(JavaTickFixture f, byte dataToKeep) {
        f.fixture.respawn(dataToKeep);
        f.player.getSetbackTeleportUtil().hasFullyLoaded = true;
        f.player.packetStateData.playerLoadedIntoLevel = true;
    }

    private static void respawnInWater(JavaTickFixture f, byte dataToKeep) {
        respawn(f, dataToKeep);
        f.place(new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO);
    }

    private static AttributeSnapshot snapshot(String name, double base, double added) {
        return new AttributeSnapshot(
                name,
                base,
                List.of(new AttributeModifier("test:modifier", added, AttributeModifier.Operation.ADD_VALUE)));
    }

    private static void attribute(JavaTickFixture f, String name, double base, double added) {
        f.player.compensatedEntities.updateAttributes(f.player.entityID, List.of(snapshot(name, base, added)));
    }

    /** An update_attributes for the player; the transaction of its trailing ping. */
    private static int sendAttribute(JavaTickFixture f, String name, double base, double added) {
        return sendAttribute(f, f.player.entityID, name, base, added);
    }

    /** An update_attributes for {@code entityId}; the transaction of its trailing ping. */
    private static int sendAttribute(JavaTickFixture f, int entityId, String name, double base, double added) {
        var packet = new ClientboundUpdateAttributes(entityId, List.of(snapshot(name, base, added)));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_ATTRIBUTES, packet, false);
        f.player.packetEntityReplication.onUpdateAttributes(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
