package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveMobEffect;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateMobEffect;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Entity#rideTick zeroes the passenger's delta movement, then LivingEntity#travel moves it by its own input and applies
 * gravity and air drag; Entity#positionRider replaces only the position. With no dismount resync written, the first
 * free move starts from that velocity.
 */
class DismountRideTickVelocityTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final int VEHICLE = 100;
    private static final Vec3 VEHICLE_AT = new Vec3(0.5, FLOOR_Y, 0.5);
    /** LivingEntity#travelInAir: one tick of gravity from a zero delta, then the 0.98 vertical drag. */
    private static final double FALL = -0.08 * 0.98F;
    /** LocalPlayer#modifyInput's 0.98 at Player#getFlyingSpeed, then the 0.91 air drag. */
    private static final double FORWARD_DRIFT = 0.98F * (double) 0.02F * 0.91F;
    /** The first free move's own forward key at Player#getFlyingSpeed. */
    private static final double FORWARD_INPUT = 0.98F * (double) 0.02F;
    /** LivingEntity#travelInAir: levitation 0 lifts a zero delta a fifth of 0.05, then the 0.98 vertical drag. */
    private static final double LEVITATION_RISE = 0.05 * 0.2 * 0.98F;

    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);

    static Stream<Arguments> endings() {
        var arguments = new ArrayList<Arguments>();
        for (ClientVersion version : List.of(ClientVersion.V_26_3, ClientVersion.V_1_21_4, ClientVersion.V_1_21_2)) {
            arguments.add(Arguments.of(version, false));
            arguments.add(Arguments.of(version, true));
        }
        return arguments.stream();
    }

    @ParameterizedTest
    @MethodSource("endings")
    void withoutInputTheFirstMoveFallsFromTheRideTickGravity(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = fixture(version);
                var ignored = observeOnly(true)) {
            ride(f);
            dismount(f, removeSeat);
            assertEquals(new Vec3(0, FALL, 0), f.player.compensatedEntities.getSelf().deltaMovement);
            PredictionResult move = f.tick(position(f).add(0, FALL, 0), false);
            assertTrue(exact(move), describe(move));
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void forwardInputOnTheRideTickCarriesItsAirDrift(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = fixture(version);
                var ignored = observeOnly(true)) {
            ride(f, FORWARD);
            dismount(f, removeSeat);
            f.input(NONE);
            PredictionResult move = f.tick(position(f).add(0, FALL, FORWARD_DRIFT), false);
            assertTrue(exact(move), describe(move));
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void forwardInputOnTheFirstMoveAddsToTheRideTickDrift(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = fixture(version);
                var ignored = observeOnly(true)) {
            ride(f, FORWARD);
            dismount(f, removeSeat);
            PredictionResult move = f.tick(position(f).add(0, FALL, FORWARD_DRIFT + FORWARD_INPUT), false);
            assertTrue(exact(move), describe(move));
        }
    }

    /** The ride tick's keys are the last player_input the client sent, so a drift with no forward key flags. */
    @ParameterizedTest
    @MethodSource("endings")
    void forwardDriftWithoutForwardInputOnTheRideTickFlags(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = fixture(version);
                var ignored = observeOnly(true)) {
            ride(f);
            dismount(f, removeSeat);
            f.input(FORWARD);
            PredictionResult move = f.tick(position(f).add(0, FALL, FORWARD_DRIFT + FORWARD_INPUT), false);
            assertTrue(move.getFlagSeverity() > 0.0, describe(move));
        }
    }

    /** Entity#move consumes a multiplier the ride tick before wrote and zeroes the delta before gravity. */
    @ParameterizedTest
    @MethodSource("endings")
    void aMultiplierTheLastRideMoveConsumedLeavesOnlyTheFall(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = fixture(version);
                var ignored = observeOnly(true)) {
            ride(f, FORWARD);
            Vec3 seat = rideTickSeat(f);
            f.player.compensatedWorld.updateBlock(
                    BlockPos.containing(seat.x, seat.y + 1.75, seat.z), BlockIds.COBWEB.defaultState());
            f.rideTick();
            f.player.compensatedWorld.updateBlock(
                    BlockPos.containing(seat.x, seat.y + 1.75, seat.z), BlockIds.AIR.defaultState());
            f.rideTick();
            dismount(f, removeSeat);
            f.input(NONE);
            PredictionResult move = f.tick(position(f).add(0, FALL, 0), false);
            assertTrue(exact(move), describe(move));
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void aMountAndDismountWithNoRideTickBetweenKeepTheOnFootVelocity(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = fixture(version);
                var ignored = observeOnly(true)) {
            f.place(VEHICLE_AT, Vec3.ZERO);
            PredictionResult standing = f.tick(VEHICLE_AT, true);
            assertTrue(exact(standing), describe(standing));
            mount(f);
            dismount(f, removeSeat);
            assertEquals(Vec3.ZERO, f.player.compensatedEntities.getSelf().deltaMovement);
        }
    }

    /** Before 1.21.4 the first ride tick keeps the mounting tick's ground state; gravity still applies. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aSingleRideTickBefore1_21_4StillLeavesTheFall(boolean removeSeat) throws Exception {
        try (var f = fixture(ClientVersion.V_1_21_2);
                var ignored = observeOnly(true)) {
            f.place(VEHICLE_AT, Vec3.ZERO);
            PredictionResult standing = f.tick(VEHICLE_AT, true);
            assertTrue(exact(standing), describe(standing));
            mount(f);
            f.rideTick();
            dismount(f, removeSeat);
            PredictionResult move = f.tick(position(f).add(0, FALL, 0), false);
            assertTrue(exact(move), describe(move));
        }
    }

    /** The ride tick's own effects set its velocity; levitation gained after it changes nothing until the next. */
    @ParameterizedTest
    @MethodSource("endings")
    void levitationGainedAfterTheLastRideTickLeavesTheFall(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = fixture(version);
                var ignored = observeOnly(true)) {
            ride(f);
            levitation(f, true);
            dismount(f, removeSeat);
            PredictionResult move = f.tick(position(f).add(0, FALL, 0), false);
            assertTrue(exact(move), describe(move));
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void levitationLostAfterTheLastRideTickLeavesTheRise(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = fixture(version);
                var ignored = observeOnly(true)) {
            ride(f);
            levitation(f, true);
            f.rideTick();
            levitation(f, false);
            dismount(f, removeSeat);
            PredictionResult move = f.tick(position(f).add(0, LEVITATION_RISE, 0), false);
            assertTrue(exact(move), describe(move));
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void levitationHeldThroughTheDismountLeavesTheRise(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = fixture(version);
                var ignored = observeOnly(true)) {
            ride(f);
            levitation(f, true);
            f.rideTick();
            dismount(f, removeSeat);
            PredictionResult move = f.tick(position(f).add(0, LEVITATION_RISE, 0), false);
            assertTrue(exact(move), describe(move));
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void waterPlacedAfterTheLastRideTickLeavesTheFall(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = fixture(version);
                var ignored = observeOnly(true)) {
            ride(f);
            Vec3 seat = rideTickSeat(f);
            BlockPos head = BlockPos.containing(seat.x, seat.y + 1, seat.z);
            f.player.compensatedWorld.updateBlock(head, BlockIds.WATER.defaultState());
            dismount(f, removeSeat);
            f.player.compensatedWorld.updateBlock(head, BlockIds.AIR.defaultState());
            PredictionResult move = f.tick(position(f).add(0, FALL, 0), false);
            assertTrue(exact(move), describe(move));
        }
    }

    /** LivingEntity#travelInWater: the 0.8 vertical drag of a zero delta, then the fluid gravity of 0.08 / 16. */
    @ParameterizedTest
    @MethodSource("endings")
    void aRideTickInStillWaterLeavesTheFluidGravity(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = fixture(version);
                var ignored = observeOnly(true)) {
            ride(f);
            pool(f, BlockIds.WATER.defaultState());
            f.rideTick();
            dismount(f, removeSeat);
            pool(f, BlockIds.AIR.defaultState());
            PredictionResult move = f.tick(position(f).add(0, -0.005, 0), false);
            assertTrue(exact(move), describe(move));
        }
    }

    /**
     * AbstractBoat#tick takes its status from the box before it moves, so a boat whose top surfaces on the last ride
     * tick is still under water for that tick and its passenger travels in water.
     */
    @ParameterizedTest
    @MethodSource("endings")
    void aBoatSurfacingOnTheLastRideTickLeavesItsPassengerInWater(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = fixture(version);
                var ignored = observeOnly(true)) {
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    for (int y = FLOOR_Y; y <= FLOOR_Y + 2; y++) {
                        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.WATER.defaultState());
                    }
                }
            }
            double surfacingY = FLOOR_Y + 2 + 8.0 / 9.0 - 0.5625;
            Vec3 under = new Vec3(0.5, surfacingY - 0.05, 0.5);
            Vec3 surfaced = new Vec3(0.5, surfacingY + 0.01, 0.5);
            f.place(VEHICLE_AT, Vec3.ZERO);
            f.tick(VEHICLE_AT, true);
            f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.OAK_BOAT, under, 0, 0, 0);
            f.setPassengers(VEHICLE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
            f.rideTick(under, false);
            f.rideTick(under, false);
            f.rideTick(surfaced, false);
            dismount(f, removeSeat);
            PredictionResult move = f.tick(position(f).add(0, -0.005, 0), false);
            assertTrue(exact(move), describe(move));
        }
    }

    /** A ride tick in powder snow travels in air, then PowderSnowBlock#entityInside sticks the first move. */
    @ParameterizedTest
    @MethodSource("endings")
    void aRideTickInPowderSnowLeavesTheFallItsMultiplierScales(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = fixture(version);
                var ignored = observeOnly(true)) {
            ride(f);
            Vec3 seat = rideTickSeat(f);
            BlockPos feet = BlockPos.containing(seat.x, seat.y, seat.z);
            f.player.compensatedWorld.updateBlock(feet, BlockIds.POWDER_SNOW.defaultState());
            f.rideTick();
            dismount(f, removeSeat);
            f.player.compensatedWorld.updateBlock(feet, BlockIds.AIR.defaultState());
            PredictionResult move = f.tick(position(f).add(0, FALL * 1.5, 0), false);
            assertTrue(exact(move), describe(move));
        }
    }

    /** LivingEntity#handleRelativeFrictionAndCalculateMovement climbs 0.2 with the jump key on a ladder. */
    @ParameterizedTest
    @MethodSource("endings")
    void aJumpOnALadderLeavesTheClimb(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = fixture(version);
                var ignored = observeOnly(true)) {
            ride(f);
            Vec3 seat = rideTickSeat(f);
            BlockPos feet = BlockPos.containing(seat.x, seat.y, seat.z);
            f.player.compensatedWorld.updateBlock(feet, BlockIds.LADDER.defaultState());
            f.input(JUMP);
            f.rideTick();
            dismount(f, removeSeat);
            f.input(NONE);
            f.player.compensatedWorld.updateBlock(feet, BlockIds.AIR.defaultState());
            PredictionResult move = f.tick(position(f).add(0, (0.2 - 0.08) * 0.98F, 0), false);
            assertTrue(exact(move), describe(move));
        }
    }

    /**
     * Entity#baseTick keeps the previous tick's box-wide powder snow contact, and LivingEntity
     * #handleRelativeFrictionAndCalculateMovement climbs 0.2 with it and the jump key while the feet are in air.
     */
    @ParameterizedTest
    @MethodSource("endings")
    void powderSnowBesideTheSeatLiftsAJumpingRider(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = fixture(version);
                var ignored = observeOnly(true)) {
            ride(f, new Vec3(0.75, FLOOR_Y, 0.5), NONE);
            Vec3 seat = rideTickSeat(f);
            BlockPos beside = BlockPos.containing(seat.x + 0.5, seat.y, seat.z);
            assertTrue(BlockPos.containing(seat.x, seat.y, seat.z).getX() != beside.getX());
            f.player.compensatedWorld.updateBlock(beside, BlockIds.POWDER_SNOW.defaultState());
            f.input(JUMP);
            f.rideTick();
            f.rideTick();
            dismount(f, removeSeat);
            f.input(NONE);
            f.player.compensatedWorld.updateBlock(beside, BlockIds.AIR.defaultState());
            PredictionResult move = f.tick(position(f).add(0, (0.2 - 0.08) * 0.98F, 0), false);
            assertTrue(exact(move), describe(move));
        }
    }

    /** ClientPacketListener#handleSetEntityMotion sets a passenger's delta, which the dismount keeps. */
    @ParameterizedTest
    @MethodSource("endings")
    void anEntityMotionAfterTheLastRideTickReplacesItsVelocity(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = fixture(version);
                var ignored = observeOnly(true)) {
            ride(f);
            var packet = new ClientboundEntityMotion(f.player.entityID, new Vec3d(0.0, 0.25, 0.0));
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_MOTION, packet, false);
            f.player.packetEntityReplication.onSetEntityMotion(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            dismount(f, removeSeat);
            assertEquals(new Vec3(0.0, 0.25, 0.0), f.player.compensatedEntities.getSelf().deltaMovement);
            PredictionResult move = f.tick(position(f).add(0, 0.25, 0), false);
            assertTrue(exact(move), describe(move));
        }
    }

    /** The dismount resync's entity motion zeroes the client's delta movement. */
    @ParameterizedTest
    @MethodSource("endings")
    void theDismountResyncZeroesTheRideTickVelocity(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = fixture(version);
                var ignored = observeOnly(false)) {
            ride(f);
            dismount(f, removeSeat);
            if (!removeSeat) assertEquals(Vec3.ZERO, f.player.compensatedEntities.getSelf().deltaMovement);
            PredictionResult move = f.tick(position(f), false);
            assertTrue(exact(move), describe(move));
        }
    }

    private static void levitation(JavaTickFixture f, boolean gain) {
        if (gain) {
            var packet = new ClientboundUpdateMobEffect(f.player.entityID, "minecraft:levitation", 0);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_MOB_EFFECT, packet, false);
            f.player.packetEntityReplication.onUpdateMobEffect(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
        } else {
            var packet = new ClientboundRemoveMobEffect(f.player.entityID, "minecraft:levitation");
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_MOB_EFFECT, packet, false);
            f.player.packetEntityReplication.onRemoveMobEffect(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
        }
        f.pongThrough(f.player.lastTransactionSent.get());
        assertEquals(gain, f.player.compensatedEntities.getSelf().potionsMap.containsKey(MovementEffect.LEVITATION));
    }

    /** {@code state} through the three by three columns around the vehicle, from the floor up five blocks. */
    private static void pool(JavaTickFixture f, int state) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 5; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), state);
                }
            }
        }
    }

    private static JavaTickFixture fixture(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.input(NONE);
        return f;
    }

    /** A player that stood at the vehicle and has ridden it for two client ticks. */
    private static void ride(JavaTickFixture f) {
        ride(f, NONE);
    }

    /** As {@link #ride(JavaTickFixture)}, with {@code input} held through the ride ticks. */
    private static void ride(JavaTickFixture f, ServerboundPlayerInput input) {
        ride(f, VEHICLE_AT, input);
    }

    /** As {@link #ride(JavaTickFixture, ServerboundPlayerInput)}, with the vehicle at {@code at}. */
    private static void ride(JavaTickFixture f, Vec3 at, ServerboundPlayerInput input) {
        f.place(at, Vec3.ZERO);
        PredictionResult standing = f.tick(at, true);
        assertTrue(exact(standing), describe(standing));
        mount(f, at);
        f.input(input);
        f.rideTick();
        f.rideTick();
    }

    private static void mount(JavaTickFixture f) {
        mount(f, VEHICLE_AT);
    }

    private static void mount(JavaTickFixture f, Vec3 at) {
        f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.ARMOR_STAND, at, 0, 0, 0);
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
    }

    private static void dismount(JavaTickFixture f, boolean removeSeat) {
        List<Integer> teleports = new ArrayList<>();
        for (TeleportData teleport : f.player.getSetbackTeleportUtil().pendingTeleports) {
            teleports.add(teleport.getTeleportId());
        }
        if (removeSeat) {
            var packet = new ClientboundRemoveEntities(List.of(VEHICLE));
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_ENTITIES, packet, false);
            f.player.packetEntityReplication.onRemoveEntities(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
        } else {
            f.setPassengers(VEHICLE, List.of());
        }
        f.pongThrough(f.player.lastTransactionSent.get());
        for (TeleportData teleport : f.player.getSetbackTeleportUtil().pendingTeleports) {
            if (teleports.contains(teleport.getTeleportId())) continue;
            Vec3 target = teleport.getLocation();
            var packet = new ServerboundAcceptTeleportation(
                    teleport.getTeleportId(),
                    new Vec3d(target.x, target.y, target.z),
                    teleport.getFinalYaw(),
                    teleport.getFinalPitch());
            var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
            new PacketServerTeleport().onAcceptTeleportation(event, f.player, packet);
            assertFalse(event.isCancelled(), "the dismount teleport acceptance was rejected");
        }
        assertFalse(f.player.compensatedEntities.getSelf().inVehicle());
    }

    private static Vec3 position(JavaTickFixture f) {
        return new Vec3(f.player.x, f.player.y, f.player.z);
    }

    private static Vec3 rideTickSeat(JavaTickFixture f) throws Exception {
        Field field = CompensatedEntities.class.getDeclaredField("rideTickSeat");
        field.setAccessible(true);
        Vec3 seat = (Vec3) field.get(f.player.compensatedEntities);
        assertTrue(seat != null, "the last client tick ended off the vehicle");
        return seat;
    }

    private static AutoCloseable observeOnly(boolean observeOnly) throws ReflectiveOperationException {
        BaseConfigManager manager = CultAPI.INSTANCE.getConfigManager();
        Field field = BaseConfigManager.class.getDeclaredField("observeOnly");
        field.setAccessible(true);
        boolean original = field.getBoolean(manager);
        field.setBoolean(manager, observeOnly);
        return () -> field.setBoolean(manager, original);
    }
}
