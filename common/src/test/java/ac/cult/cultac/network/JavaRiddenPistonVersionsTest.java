package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * PistonMovingBlockEntity#moveCollidedEntities also moves a locally controlled root vehicle. LocalPlayer reports
 * that root before the block-entity phase through 26.2, and after it in 26.3's sendChanges.
 */
class JavaRiddenPistonVersionsTest {
    private static final int HORSE = 100;
    private static final double HALF_WIDTH = 1.3964844F / 2.0;
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final BlockPos PISTON = new BlockPos(0, FLOOR_Y, 2);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    /** A horse movement_speed (HorseSoulSandEdgeTest's captured one). */
    private static final float SPEED = 0.14814077F;
    /** LivingEntity#travelInAir on a 0.6-friction block. */
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    /** LivingEntity#travelInAir: a grounded horse's (0 - 0.08) * 0.98F. */
    private static final double GROUNDED_Y = -0.0784000015258789;
    /** LivingEntity#moveRelative of the ridden input's 0.98F at yaw 0 on 0.6-friction ground. */
    private static final double GROUND_INPUT = (double) 0.98F * (SPEED * (0.21600002F / (0.6F * 0.6F * 0.6F)));

    @ParameterizedTest(name = "{0}: a piston moves the local horse using its own box")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPistonPushesTheLocallyControlledHorse(String release, ClientVersion version) throws Exception {
        pushesRoot(version, EntityTypeIds.HORSE, HALF_WIDTH);
    }

    @ParameterizedTest(name = "{0}: a piston moves the local boat using its own box")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPistonPushesTheLocallyControlledBoat(String release, ClientVersion version) throws Exception {
        pushesRoot(version, EntityTypeIds.OAK_BOAT, 1.375F / 2.0);
    }

    /** The live repeated-piston course resets the same ridden root with an ordinary server teleport. */
    @ParameterizedTest(name = "{0}: a ridden teleport reports its interpolation target")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aTeleportTargetIsNotMistakenForTheHorsesPhysicalTravel(String release, ClientVersion version)
            throws Exception {
        try (var f = mounted(version, EntityTypeIds.HORSE)) {
            Vec3 target = new Vec3(0.5, FLOOR_Y, 1.1);
            teleport(f, target, Vec3.ZERO);
            var root = f.player.compensatedEntities.getEntity(HORSE);
            for (int tick = 1; tick <= 3; tick++) {
                var result = f.rideTick(target, tick != 1);
                assertTrue(exact(result), describe(result));
                double fraction = version.isNewerThanOrEquals(ClientVersion.V_26_3) ? (float) tick / 3.0F : tick / 3.0;
                assertEquals(
                        0.1 + fraction,
                        root.clientPhysicalPosition.z,
                        1e-10,
                        "a target report must preserve the real interpolated root position");
            }
        }
    }

    @ParameterizedTest(name = "{0}: an interpolation report cannot claim a different target")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aWrongInterpolationTargetIsRejected(String release, ClientVersion version) throws Exception {
        try (var f = mounted(version, EntityTypeIds.HORSE)) {
            Vec3 target = new Vec3(0.5, FLOOR_Y, 1.1);
            teleport(f, target, Vec3.ZERO);
            var result = f.rideTick(target.add(0.01, 0, 0), false);
            assertRejected(result, "VehicleOffset");
            assertEquals(0.01, result.getOffset(), 1e-10, "the source packet target has no movement-input radius");
        }
    }

    /** A teleport writes motion, and interpolation does not consume that motion as travel. */
    @ParameterizedTest(name = "{0}: real motion survives all three interpolation steps")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void interpolationCarriesTheHorsesPhysicalMotionRatherThanTheReportedPath(String release, ClientVersion version)
            throws Exception {
        try (var f = mounted(version, EntityTypeIds.HORSE)) {
            teleport(f, new Vec3(0.5, FLOOR_Y, 1.1), new Vec3(0, 0, 0.1));
            double velocity = 0.1;
            double previousTravels = 0;
            double physical = 0.1;
            var root = f.player.compensatedEntities.getEntity(HORSE);
            for (int tick = 1; tick <= 3; tick++) {
                double pathTarget = 1.1;
                if (version.isNewerThanOrEquals(ClientVersion.V_1_21_5)) {
                    pathTarget += previousTravels;
                    double fraction =
                            version.isNewerThanOrEquals(ClientVersion.V_26_3) ? (float) tick / 3.0F : tick / 3.0;
                    physical = 0.1 + previousTravels + fraction;
                } else {
                    // LivingEntity's legacy lerp has a fixed target and starts each step at its physical position.
                    physical += (pathTarget - physical) / (4 - tick);
                }
                physical += velocity;
                Vec3 report = new Vec3(0.5, FLOOR_Y, tick < 3 ? pathTarget : physical);
                var result = f.rideTick(report, tick != 1);
                assertTrue(
                        exact(result),
                        describe(result) + " tick=" + tick
                                + " start=" + result.getSimulationContext().getStart()
                                + " report=" + report + " physical=" + root.clientPhysicalPosition
                                + " accepted=" + result.getAcceptedClosestToTarget()
                                + " flags=" + result.getFlags());
                assertEquals(
                        velocity,
                        result.getAcceptedClosestToTarget().z,
                        1e-10,
                        "the physical trace must keep motion even while the packet only reports the target");
                assertEquals(physical, root.clientPhysicalPosition.z, 1e-10);
                previousTravels += velocity;
                velocity *= tick == 1 ? (double) (0.6F * 0.91F) : (double) 0.91F;
            }
        }
    }

    private static void teleport(JavaTickFixture f, Vec3 target, Vec3 velocity) {
        teleport(f, target, velocity, 0);
    }

    private static void teleport(JavaTickFixture f, Vec3 target, Vec3 velocity, float yaw) {
        teleport(f, target, velocity, yaw, 0);
    }

    private static void teleport(JavaTickFixture f, Vec3 target, Vec3 velocity, float yaw, float pitch) {
        var packet = new ClientboundTeleportEntity(
                HORSE,
                new Vec3d(target.x, target.y, target.z),
                new Vec3d(velocity.x, velocity.y, velocity.z),
                yaw,
                pitch,
                Set.of(),
                true);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.TELEPORT_ENTITY, packet, false);
        f.player.packetEntityReplication.onTeleportEntity(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    @ParameterizedTest(name = "{0}: target yaw does not replace the controller's physical horse yaw")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void anInterpolatingHorseTurnsToItsControllerEvenWhenThePacketReportsTheTargetYaw(
            String release, ClientVersion version) throws Exception {
        try (var f = mounted(version, EntityTypeIds.HORSE)) {
            Vec3 target = new Vec3(0.5, FLOOR_Y, 1.1);
            teleport(f, target, Vec3.ZERO, 90, 40);
            var rot = new ServerboundMovePlayer(0, 0, 0, 0, 20, false, false, false, true);
            f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
            float reportedYaw = version.isNewerThanOrEquals(ClientVersion.V_1_21_5) ? 90 : 0;
            float reportedPitch = version.isNewerThanOrEquals(ClientVersion.V_1_21_5) ? 40 : 10;
            var move = new ServerboundMoveVehicle(
                    new Vec3d(target.x, target.y, target.z), reportedYaw, reportedPitch, false, true);
            f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
            f.tickEnd();
            var result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            assertTrue(exact(result), describe(result));
            assertEquals(
                    0,
                    f.player.compensatedEntities.getEntity(HORSE).clientPhysicalYaw,
                    "AbstractHorse#tickRidden turns to the controller before travel");
            assertEquals(
                    10,
                    f.player.compensatedEntities.getEntity(HORSE).clientPhysicalPitch,
                    "AbstractHorse#getRiddenRotation halves the controller's pitch");
        }
    }

    @ParameterizedTest(name = "{0}: a piston changes an active interpolation report when the target is free")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPistonDuringInterpolationMovesThePhysicalRootAndItsSourceReport(String release, ClientVersion version)
            throws Exception {
        pistonDuringInterpolation(version, 0.4, false);
    }

    @ParameterizedTest(name = "{0}: a blocked interpolation target stays fixed while the piston moves the root")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPistonCannotTranslateAnInterpolationTargetIntoThePistonBase(String release, ClientVersion version)
            throws Exception {
        pistonDuringInterpolation(version, 2.4, true);
    }

    @ParameterizedTest(name = "{0}: a piston carries the boat's active interpolation target")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPistonDuringBoatInterpolationCarriesTheLinearPath(String release, ClientVersion version) throws Exception {
        pistonDuringInterpolation(version, 0.4, false, EntityTypeIds.OAK_BOAT, 1.375F / 2);
    }

    @ParameterizedTest(name = "{0}: a piston cannot carry the boat's target into the base")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aBlockedBoatInterpolationTargetStaysFixed(String release, ClientVersion version) throws Exception {
        pistonDuringInterpolation(version, 2.4, true, EntityTypeIds.OAK_BOAT, 1.375F / 2);
    }

    private static void pistonDuringInterpolation(ClientVersion version, double targetZ, boolean blocked)
            throws Exception {
        pistonDuringInterpolation(version, targetZ, blocked, EntityTypeIds.HORSE, HALF_WIDTH);
    }

    private static void pistonDuringInterpolation(
            ClientVersion version, double targetZ, boolean blocked, int type, double halfWidth) throws Exception {
        try (var f = mounted(version, type)) {
            int piston = piston(f, "minecraft:stone");
            teleport(f, new Vec3(0.5, FLOOR_Y, targetZ), Vec3.ZERO);
            boolean stepped = type == EntityTypeIds.HORSE && version.isNewerThanOrEquals(ClientVersion.V_26_3);
            double fraction = stepped ? 1.0F / 3.0F : 1.0 / 3.0;
            double interpolated = 0.1 + (targetZ - 0.1) * fraction;
            double shove = -(Math.min(interpolated + halfWidth - 0.5, 0.5) + 0.01);
            double reportTarget =
                    version.isNewerThanOrEquals(ClientVersion.V_26_3) && !blocked ? targetZ + shove : targetZ;
            extend(f, piston);
            // The boat's travel lands; the later horizontal piston move clears ground before 26.3's report.
            boolean reportedGround = type == EntityTypeIds.OAK_BOAT && version.isOlderThan(ClientVersion.V_26_3);
            var first = f.rideTick(new Vec3(0.5, FLOOR_Y, reportTarget), reportedGround);
            assertTrue(exact(first), describe(first));
            var root = f.player.compensatedEntities.getEntity(HORSE);
            double physical = interpolated + shove;
            assertEquals(physical, root.clientPhysicalPosition.z, 1e-10);

            // Ordinary block updates cancel the remaining two moving block entities before the next tick.
            int air = REGISTRY.block("minecraft:air").defaultState();
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), air);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 1), air);
            // 1.21.5's noCollision test adds the current position twice, placing this box above the base.
            boolean shifted = (!blocked || version == ClientVersion.V_1_21_5)
                    && version.isNewerThanOrEquals(ClientVersion.V_1_21_5);
            double finalTarget = shifted ? targetZ + shove : targetZ;
            double secondPosition = stepped
                    ? 0.1 + (shifted ? shove : 0) + (targetZ - 0.1) * (2.0F / 3.0F)
                    : physical + (finalTarget - physical) / 2.0;
            var second = f.rideTick(new Vec3(0.5, FLOOR_Y, finalTarget), true);
            assertTrue(exact(second), describe(second));
            assertEquals(
                    secondPosition,
                    root.clientPhysicalPosition.z,
                    1e-10,
                    "the piston must update the interpolation anchor exactly once");
            var third = f.rideTick(new Vec3(0.5, FLOOR_Y, finalTarget), true);
            assertTrue(exact(third), describe(third));
            assertEquals(finalTarget, root.clientPhysicalPosition.z, 1e-10);
        }
    }

    /** A minecart has no local controller: LocalPlayer sends only Rot, but the same piston still moves its root. */
    @ParameterizedTest(name = "{0}: a passive riding root still receives its piston pass")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPistonPushesTheMinecartUnderAPassiveLocalPassenger(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.1);
            f.place(position, Vec3.ZERO);
            f.player.compensatedWorld.applyClientTime(new WorldPackets.TimeUpdate(24_000L, Map.of()));
            f.player.compensatedEntities.addEntity(
                    HORSE, EntityTypes.defaults().byKey("minecraft:minecart").id(), position, 0, 0, 0);
            var root = f.player.compensatedEntities.getEntity(HORSE);
            root.onGround = true;
            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(NONE);
            for (int tick = 0; tick < 6; tick++) f.rideTick();
            int piston = piston(f, "minecraft:stone");
            extend(f, piston);
            f.rideTick();
            Vec3 first = new Vec3(0.5, FLOOR_Y, 0.5 - 0.98F / 2 - 0.01);
            assertEquals(first.z, root.clientPhysicalPosition.z, 1e-10, "the root moves without a vehicle report");
            f.rideTick();
            assertEquals(first.z - 0.5, root.clientPhysicalPosition.z, 1e-10, "the next piston tick starts there");
        }
    }

    @ParameterizedTest(name = "{0}: slime writes the controlled horse's actual velocity")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void slimeLaunchesTheHorseRatherThanOnlyMovingItsReportedPosition(String release, ClientVersion version)
            throws Exception {
        try (var f = mounted(version, EntityTypeIds.HORSE)) {
            int piston = piston(f, "minecraft:slime_block");
            Vec3 first = new Vec3(0.5, FLOOR_Y, 0.5 - HALF_WIDTH - 0.01);
            extend(f, piston);
            if (version.isOlderThan(ClientVersion.V_26_3)) assertExact(f.rideTick(new Vec3(0.5, FLOOR_Y, 0.1), true));
            else assertExact(f.rideTick(first, false));
            for (Vec3 velocity : f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels())
                assertEquals(-1.0, velocity.z, 1e-12, "slime sets the root's deltaMovement on every compatible carry");
            // Through 1.21.4 Mob#isEffectiveAi is false on the client, so LivingEntity#aiStep
            // multiplies a non-interpolating horse's stored delta by DOUBLE 0.98 before travelRidden.
            double travel = version.isOlderThan(ClientVersion.V_1_21_5) ? -0.98 : -1.0;
            assertExact(f.rideTick(first.add(0, 0, travel), true));
        }
    }

    private static void pushesRoot(ClientVersion version, int type, double halfWidth) throws Exception {
        try (var f = mounted(version, type)) {
            int piston = piston(f, "minecraft:stone");
            Vec3 first = new Vec3(0.5, FLOOR_Y, 0.5 - halfWidth - 0.01);
            Vec3 second = first.add(0, 0, -0.5);
            extend(f, piston);
            if (version.isOlderThan(ClientVersion.V_26_3)) assertExact(f.rideTick(new Vec3(0.5, FLOOR_Y, 0.1), true));
            assertExact(f.rideTick(first, version.isOlderThan(ClientVersion.V_26_3)));
            assertExact(f.rideTick(second, version.isOlderThan(ClientVersion.V_26_3)));
            assertExact(f.rideTick(second, true));
        }
    }

    /**
     * The saddled horse moves south with its ridden input while a piston behind it pushes it south too. Each pushed
     * tick, LivingEntity#travelRidden runs one moveRelative with AbstractHorse#getRiddenInput, then
     * PistonMovingBlockEntity#moveCollidedEntities fixes Z 0.01 past the moving face. Entity#move(PISTON) leaves
     * deltaMovement alone, so the free ticks after show the velocity those travels carried: one ridden input each.
     */
    @org.junit.jupiter.api.Test
    void aPistonPushingTheRiddenHorseAlongItsTravelCarriesOneRiddenInput() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = new Vec3(0.5, FLOOR_Y, 0.9);
            f.place(start, Vec3.ZERO);
            f.player.compensatedWorld.applyClientTime(new WorldPackets.TimeUpdate(24_000L, Map.of()));
            f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, start, 0, 0, 0);
            var horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
            horse.hasSaddle = true;
            horse.isTame = true;
            horse.movementSpeedAttribute = SPEED;
            horse.onGround = true;
            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            // AbstractHorse#tick runs before LocalPlayer#rideTick hands it this tick's keys.
            f.input(FORWARD_SPRINT);
            f.tickEnd();
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0, GROUNDED_Y, 0));
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            BlockPos base = new BlockPos(0, FLOOR_Y, -2);
            extend(f, base, 3, piston(f, base, "south", "minecraft:stone"));

            double velocity = GROUND_INPUT;
            double z = 0.5 + 0.01 + HALF_WIDTH;
            assertExact(f.rideTick(new Vec3(0.5, FLOOR_Y, z), true));
            velocity = velocity * GROUND_FRICTION + GROUND_INPUT;
            z = 1.0 + 0.01 + HALF_WIDTH;
            assertExact(f.rideTick(new Vec3(0.5, FLOOR_Y, z), true));
            for (int tick = 0; tick < 2; tick++) {
                velocity = velocity * GROUND_FRICTION + GROUND_INPUT;
                z += velocity;
                assertExact(f.rideTick(new Vec3(0.5, FLOOR_Y, z), true));
            }
        }
    }

    private static int piston(JavaTickFixture f, String movedBlock) {
        return piston(f, PISTON, "north", movedBlock);
    }

    private static int piston(JavaTickFixture f, BlockPos base, String facing, String movedBlock) {
        int piston = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", facing);
        f.player.compensatedWorld.updateBlock(base, piston);
        BlockPos moved = facing.equals("north") ? base.north() : base.south();
        f.player.compensatedWorld.updateBlock(moved, REGISTRY.block(movedBlock).defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        return piston;
    }

    private static JavaTickFixture mounted(ClientVersion version, int type) throws Exception {
        var f = new JavaTickFixture();
        boolean ready = false;
        try {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.1);
            f.place(position, Vec3.ZERO);
            f.player.compensatedWorld.applyClientTime(new WorldPackets.TimeUpdate(24_000L, Map.of()));
            f.player.compensatedEntities.addEntity(HORSE, type, position, 0, 0, 0);
            var root = f.player.compensatedEntities.getEntity(HORSE);
            if (root instanceof PacketEntityHorse horse) horse.hasSaddle = true;
            root.onGround = true;
            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(NONE);
            for (int tick = 0; tick < 6; tick++) assertExact(f.rideTick(position, true));
            ready = true;
            return f;
        } finally {
            if (!ready) f.close();
        }
    }

    private static void extend(JavaTickFixture f, int piston) {
        extend(f, PISTON, 2, piston);
    }

    /** PistonBaseBlock#triggerEvent's extend event; {@code direction} is the Direction 3D data value. */
    private static void extend(JavaTickFixture f, BlockPos base, int direction, int piston) {
        var packet = new ClientboundBlockEvent(base, 0, direction, REGISTRY.blockIndex(piston));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketBlockAction().onBlockEvent(event, f.player, packet);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void assertExact(PredictionResult result) {
        JavaTickFixture.assertVehicleExact(result, 0);
    }
}
