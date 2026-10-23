package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveMobEffect;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateMobEffect;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySizeable;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.RideTickStuckContact;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A dismount after a ride tick carries no fall distance and lets the first move take no multiplier or one a block near
 * the last ride tick's travel gives.
 */
class DismountRideStateTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, false, false);
    private static final int VEHICLE = 100;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y, 0.5);
    /** A set_entity_motion the first free move scales by the carried multiplier, far past any input allowance. */
    private static final double PUSH = 0.5;

    private static final double FALL = -0.08 * 0.98F;
    private static final Vec3 BERRY_BUSH = new Vec3(0.8F, 0.75, 0.8F);
    private static final Vec3 WEAVING_COBWEB = new Vec3(0.5, 0.25, 0.5);
    private static final Vec3 COBWEB = new Vec3(0.25, 0.05F, 0.25);
    private static final Vec3 POWDER_SNOW = new Vec3(0.9F, 1.5, 0.9F);
    /** A set_entity_motion the first free move lifts by powder snow's 1.5 vertical multiplier. */
    private static final double LIFT = 0.4;

    /** 26.3 carries exact inside-block effects; 1.21.4 carries the last required stuck speed. */
    static Stream<Arguments> endings() {
        var arguments = new ArrayList<Arguments>();
        for (ClientVersion version : List.of(ClientVersion.V_26_3, ClientVersion.V_1_21_4)) {
            arguments.add(Arguments.of(version, false));
            arguments.add(Arguments.of(version, true));
        }
        return arguments.stream();
    }

    @ParameterizedTest
    @MethodSource("endings")
    void aMountAndDismountWithNoRideTickBetweenKeepTheCobwebMultiplier(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = fixture(version)) {
            Vec3 inWeb = new Vec3(0.5, FLOOR_Y + 1.5, 0.5);
            web(f, BlockPos.containing(inWeb.x, inWeb.y, inWeb.z));
            f.place(inWeb, Vec3.ZERO);
            PredictionResult stuck = f.tick(inWeb, false);
            assertTrue(exact(stuck), describe(stuck));

            mount(f, SEAT);
            dismount(f, removeSeat);
            Vec3 web = Collisions.getCobwebStuckSpeed(f.player);
            assertCarriedStuckSpeed(f, version, web);
            pushedMove(f, web);
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void aMountAndDismountWithNoRideTickBetweenKeepTheFallDistance(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = fixture(version)) {
            double fallDistance = fall(f);
            mount(f, SEAT);
            dismount(f, removeSeat);
            JavaPredictionCarry carry = carry(f);
            assertEquals(fallDistance, carry.fallDistance());
            for (var state : carry.states()) assertEquals(fallDistance, state.fallDistance());
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void aRideTickResetsTheFallDistance(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = fixture(version)) {
            fall(f);
            mount(f, SEAT);
            f.rideTick();
            dismount(f, removeSeat);
            JavaPredictionCarry carry = carry(f);
            assertEquals(0.0D, carry.fallDistance());
            for (var state : carry.states()) assertEquals(0.0D, state.fallDistance());
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void aCobwebMultiplierFromBeforeTheRideIsConsumedByIt(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = fixture(version)) {
            web(f, new BlockPos(0, FLOOR_Y, 0));
            f.place(SEAT, Vec3.ZERO);
            PredictionResult inWeb = f.tick(SEAT, true);
            assertTrue(exact(inWeb), describe(inWeb));
            mount(f, SEAT);
            f.rideTick();
            f.rideTick();
            dismount(f, removeSeat);
            pushedMove(f, null);
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void aRideWithNoStuckBlockNearLeavesTheFirstMoveUnslowed(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = riding(version, SEAT)) {
            f.rideTick();
            dismount(f, removeSeat);
            assertCarriedStuckCandidates(f, version);
            pushedMove(f, null);
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void aCobwebAtTheSeatCanSlowTheFirstMove(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = riding(version, SEAT)) {
            web(f, seatTop(rideTickSeat(f)));
            f.rideTick();
            dismount(f, removeSeat);
            Vec3 web = Collisions.getCobwebStuckSpeed(f.player);
            assertCarriedStuckCandidates(f, version, COBWEB, WEAVING_COBWEB);
            pushedMove(f, web);
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void aCobwebAtTheSeatCanLeaveTheFirstMoveUnslowed(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = riding(version, SEAT)) {
            web(f, seatTop(rideTickSeat(f)));
            f.rideTick();
            dismount(f, removeSeat);
            pushedMove(f, null);
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void aCobwebAddedAfterTheLastRideTickDoesNotSlowTheFirstMove(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = riding(version, SEAT)) {
            web(f, seatTop(rideTickSeat(f)));
            dismount(f, removeSeat);
            assertCarriedStuckCandidates(f, version);
            pushedMove(f, null);
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void aCobwebRemovedAfterTheLastRideTickStillSlowsTheFirstMove(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = riding(version, SEAT)) {
            BlockPos top = seatTop(rideTickSeat(f));
            web(f, top);
            f.rideTick();
            f.player.compensatedWorld.updateBlock(top, BlockIds.AIR.defaultState());
            dismount(f, removeSeat);
            Vec3 web = Collisions.getCobwebStuckSpeed(f.player);
            assertCarriedStuckCandidates(f, version, COBWEB, WEAVING_COBWEB);
            pushedMove(f, web);
        }
    }

    /** ClientLevel#tickPassenger runs the ride tick before positionRider moves the player to the new seat. */
    @ParameterizedTest
    @MethodSource("endings")
    void aMovingSeatTakesTheCobwebAtThePriorSeat(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = riding(version, SEAT)) {
            Vec3 prior = rideTickSeat(f);
            BlockPos top = seatTop(prior);
            teleportVehicle(f, SEAT.add(12, 0, 0));
            web(f, top);
            f.rideTick();
            Vec3 seat = rideTickSeat(f);
            assertFalse(
                    playerBox(f, seat).isIntersected(new SimpleCollisionBox(top)),
                    "the new seat " + seat + " still touches the cobweb");
            dismount(f, removeSeat);
            Vec3 web = Collisions.getCobwebStuckSpeed(f.player);
            assertCarriedStuckCandidates(f, version, COBWEB, WEAVING_COBWEB);
            pushedMove(f, web);
        }
    }

    /** BlockGetter#forEachBlockIntersectedBetween visits X fastest, then Y, then Z; the last stuck callback wins. */
    @ParameterizedTest
    @MethodSource("endings")
    void aBerryBushVisitedAfterACobwebSetsTheCarriedMultiplier(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = riding(version, new Vec3(0.9, FLOOR_Y, 0.9))) {
            int y = seatTop(rideTickSeat(f)).getY();
            web(f, new BlockPos(1, y, 0));
            f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 1), BlockIds.SWEET_BERRY_BUSH.defaultState());
            f.rideTick();
            dismount(f, removeSeat);
            assertCarriedStuckCandidates(f, version, COBWEB, WEAVING_COBWEB, BERRY_BUSH);
            pushedMove(f, BERRY_BUSH);
        }
    }

    /** LivingEntity#travel moves a passenger by its own keys before the inside-block pass. */
    @ParameterizedTest
    @MethodSource("endings")
    void aPassengersOwnTravelReachesACobweb(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = fixture(version)) {
            double halfWidth = playerBox(f, Vec3.ZERO).maxX;
            ride(f, new Vec3(0.99 - halfWidth, FLOOR_Y, 0.5));
            Vec3 seat = rideTickSeat(f);
            assertTrue(playerBox(f, seat).maxX < 1.0 - 1.0E-3, "the seat box clears the cobweb");
            web(f, new BlockPos(1, seatTop(seat).getY(), 0));
            f.input(LEFT);
            f.rideTick();
            f.input(NONE);
            dismount(f, removeSeat);
            pushedMove(f, Collisions.getCobwebStuckSpeed(f.player));
        }
    }

    /** Entity#collide stops the passenger's travel at the wall, short of the cobweb beside its upper body. */
    @ParameterizedTest
    @MethodSource("endings")
    void aWallClipsTravelBeforeTheUpperCobweb(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = fixture(version)) {
            double halfWidth = playerBox(f, Vec3.ZERO).maxX;
            ride(f, new Vec3(0.99 - halfWidth, FLOOR_Y, 0.5));
            Vec3 seat = rideTickSeat(f);
            BlockPos wall = BlockPos.containing(1, seat.y, 0);
            BlockPos top = new BlockPos(1, seatTop(seat).getY(), 0);
            assertNotEquals(wall, top);
            f.player.compensatedWorld.updateBlock(wall, BlockIds.STONE.defaultState());
            web(f, top);
            f.input(LEFT);
            f.rideTick();
            f.input(NONE);
            f.player.compensatedWorld.updateBlock(wall, BlockIds.AIR.defaultState());
            dismount(f, removeSeat);
            pushedMove(f, null);
        }
    }

    /** Player#updatePlayerPose stands a passenger up only after its first ride tick's inside-block pass. */
    @ParameterizedTest
    @MethodSource("endings")
    void releasingCrouchOnTheSeatReachesTheCobwebAboveTheCrouchingBox(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = fixture(version)) {
            f.place(SEAT, Vec3.ZERO);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, true, false));
            PredictionResult crouching = f.tick(SEAT, true);
            assertTrue(exact(crouching), describe(crouching));
            assertEquals(1.5F, f.player.pose.height);
            mount(f, SEAT);
            f.input(NONE);
            f.rideTick();
            f.rideTick();
            Vec3 seat = rideTickSeat(f);
            BlockPos top = seatTop(seat);
            assertTrue(playerBox(f, seat).maxY < top.getY(), "the crouching box reaches the cobweb");
            web(f, top);
            f.rideTick();
            dismount(f, removeSeat);
            pushedMove(f, Collisions.getCobwebStuckSpeed(f.player));
        }
    }

    /** AbstractBoat#positionRider turns the passenger after its travel, so the yaw it sends is not its travel's. */
    @ParameterizedTest
    @MethodSource("endings")
    void aTurningBoatsPassengerMissesTheCobwebItsReportedYawWouldReach(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = fixture(version)) {
            double halfWidth = playerBox(f, Vec3.ZERO).maxX;
            ride(f, new Vec3(0.5, FLOOR_Y, 1.0 - 2.0E-4 - halfWidth), EntityTypeIds.OAK_BOAT);
            Vec3 seat = rideTickSeat(f);
            web(f, new BlockPos(0, seatTop(seat).getY(), 1));
            f.player.xRot = 1;
            f.input(LEFT);
            f.rideTick();
            f.input(NONE);
            dismount(f, removeSeat);
            pushedMove(f, null);
        }
    }

    /** LivingEntity#jumpInLiquid lifts a passenger in water before its travel. */
    @ParameterizedTest
    @MethodSource("endings")
    void aFluidJumpReachesTheCobwebAboveTheSeat(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = fixture(version)) {
            f.place(SEAT, Vec3.ZERO);
            PredictionResult standing = f.tick(SEAT, true);
            assertTrue(exact(standing), describe(standing));
            mount(f, SEAT.add(0, 0.805, 0));
            f.rideTick();
            f.rideTick();
            Vec3 seat = rideTickSeat(f);
            int webY = (int) Math.floor(seat.y + 1.8F) + 1;
            double gap = webY - playerBox(f, seat).maxY;
            assertTrue(gap > 0.01 && gap < 0.03, "the standing box is " + gap + " below the cobweb");
            for (int y = (int) Math.floor(seat.y); y < webY; y++)
                f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), BlockIds.WATER.defaultState());
            web(f, new BlockPos(0, webY, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, true, false, false));
            f.rideTick();
            f.input(NONE);
            dismount(f, removeSeat);
            assertCarriedStuckCandidates(f, version, COBWEB, WEAVING_COBWEB, BERRY_BUSH, POWDER_SNOW);
            pushedMove(f, Collisions.getCobwebStuckSpeed(f.player));
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void aMoveFasterThanUnslowedPhysicsStillFlagsAfterADismount(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = riding(version, SEAT)) {
            web(f, seatTop(rideTickSeat(f)));
            f.rideTick();
            dismount(f, removeSeat);
            f.pongThrough(f.velocity(new Vec3(PUSH, 0, 0)));
            Vec3 from = new Vec3(f.player.x, f.player.y, f.player.z);
            PredictionResult move = f.tick(from.add(PUSH * 1.2, 0, 0), false);
            assertTrue(move.getFlagSeverity() > 0.0, describe(move));
        }
    }

    /** WebBlock#entityInside takes Weaving at the ride tick, so an effect the dismount tick gains does not apply. */
    @ParameterizedTest
    @MethodSource("endings")
    void weavingGainedAfterTheLastRideTickKeepsTheOrdinaryCobwebMultiplier(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = riding(version, SEAT)) {
            web(f, seatTop(rideTickSeat(f)));
            f.rideTick();
            Vec3 ordinary = Collisions.getCobwebStuckSpeed(f.player);
            weaving(f, true);
            dismount(f, removeSeat);
            pushedMove(f, ordinary);
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void weavingLostAfterTheLastRideTickKeepsTheWeavingCobwebMultiplier(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = riding(version, SEAT)) {
            weaving(f, true);
            web(f, seatTop(rideTickSeat(f)));
            f.rideTick();
            weaving(f, false);
            dismount(f, removeSeat);
            pushedMove(f, WEAVING_COBWEB);
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void weavingHeldThroughTheDismountKeepsTheWeavingCobwebMultiplier(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = riding(version, SEAT)) {
            weaving(f, true);
            web(f, seatTop(rideTickSeat(f)));
            f.rideTick();
            dismount(f, removeSeat);
            pushedMove(f, WEAVING_COBWEB);
        }
    }

    /** LivingEntity#aiStep jumps from the ground in water shallower than the fluid jump threshold. */
    @ParameterizedTest
    @MethodSource("endings")
    void aShallowWaterJumpReachesACobwebAQuarterBlockAboveTheSeat(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = fixture(version)) {
            f.place(SEAT, Vec3.ZERO);
            PredictionResult standing = f.tick(SEAT, true);
            assertTrue(exact(standing), describe(standing));
            mount(f, SEAT.add(0, 0.495, 0));
            f.rideTick();
            f.rideTick();
            Vec3 seat = rideTickSeat(f);
            int webY = (int) Math.floor(seat.y + 1.8F) + 1;
            double gap = webY - playerBox(f, seat).maxY;
            double waterHeight = Math.floor(seat.y) + 8.0 / 9.0 - seat.y;
            assertTrue(gap > 0.25 && gap < 0.42, "the standing box is " + gap + " below the cobweb");
            assertTrue(waterHeight > 0.001 && waterHeight < 0.4, "the seat is " + waterHeight + " under water");
            f.player.compensatedWorld.updateBlock(
                    BlockPos.containing(seat.x, seat.y, seat.z), BlockIds.WATER.defaultState());
            web(f, new BlockPos(0, webY, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, true, false, false));
            f.rideTick();
            f.input(NONE);
            dismount(f, removeSeat);
            pushedMove(f, Collisions.getCobwebStuckSpeed(f.player));
        }
    }

    /** The vehicle moves before ClientLevel#tickPassenger runs the ride tick from the prior seat. */
    @ParameterizedTest
    @MethodSource("endings")
    void aSeatTeleportedFarBeforeTheLastRideTickCarriesThePriorSeatsCobweb(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = riding(version, SEAT)) {
            web(f, seatTop(rideTickSeat(f)));
            teleportVehicle(f, SEAT.add(0, 1000, 0));
            f.rideTick();
            dismount(f, removeSeat);
            assertCarriedStuckCandidates(f, version, COBWEB, WEAVING_COBWEB);
            pushedMove(f, Collisions.getCobwebStuckSpeed(f.player));
        }
    }

    /** Mounting and leaving a seat in open air touches no powder snow, so a lift it would scale flags. */
    @ParameterizedTest
    @MethodSource("endings")
    void anOpenAirRideCannotClaimThePowderSnowLift(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = riding(version, SEAT)) {
            f.rideTick();
            dismount(f, removeSeat);
            assertCarriedStuckCandidates(f, version);
            PredictionResult move = liftedMove(f, POWDER_SNOW.y);
            assertTrue(move.getFlagSeverity() > 0.0, describe(move));
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void anOpenAirRideOfOneTickCannotClaimThePowderSnowLift(ClientVersion version, boolean removeSeat)
            throws Exception {
        try (var f = fixture(version)) {
            f.place(SEAT, Vec3.ZERO);
            PredictionResult standing = f.tick(SEAT, true);
            assertTrue(exact(standing), describe(standing));
            mount(f, SEAT);
            f.rideTick();
            dismount(f, removeSeat);
            assertCarriedStuckCandidates(f, version);
            PredictionResult move = liftedMove(f, POWDER_SNOW.y);
            assertTrue(move.getFlagSeverity() > 0.0, describe(move));
        }
    }

    /** PowderSnowBlock#entityInside sticks a living entity whose feet are in the powder snow. */
    @ParameterizedTest
    @MethodSource("endings")
    void powderSnowAtTheSeatLiftsTheFirstMove(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = riding(version, SEAT)) {
            Vec3 seat = rideTickSeat(f);
            f.player.compensatedWorld.updateBlock(
                    BlockPos.containing(seat.x, seat.y, seat.z), BlockIds.POWDER_SNOW.defaultState());
            f.rideTick();
            dismount(f, removeSeat);
            assertCarriedStuckCandidates(f, version, POWDER_SNOW);
            PredictionResult move = liftedMove(f, POWDER_SNOW.y);
            assertTrue(exact(move), describe(move));
        }
    }

    @ParameterizedTest
    @MethodSource("endings")
    void anOpenAirRideLeavesTheLiftUnscaled(ClientVersion version, boolean removeSeat) throws Exception {
        try (var f = riding(version, SEAT)) {
            f.rideTick();
            dismount(f, removeSeat);
            PredictionResult move = liftedMove(f, 1.0);
            assertTrue(exact(move), describe(move));
        }
    }

    /** Before 1.21.4 the first ride tick keeps the mounting tick's ground state and travels at the ground speed. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aLegacyFirstRideAtAHighMovementSpeedReachesAFarCobweb(boolean removeSeat) throws Exception {
        try (var f = fixture(ClientVersion.V_1_21_2)) {
            f.place(SEAT, Vec3.ZERO);
            assertTrue(exact(f.tick(SEAT, true)));
            var packet = new ClientboundUpdateAttributes(
                    f.player.entityID, List.of(new AttributeSnapshot("minecraft:movement_speed", 1.0, List.of())));
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_ATTRIBUTES, packet, false);
            f.player.packetEntityReplication.onUpdateAttributes(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(1.0, f.player.compensatedEntities.getSelf().playerSpeed);
            PredictionResult standing = f.tick(SEAT, true);
            assertTrue(exact(standing), describe(standing));
            web(f, new BlockPos(1, FLOOR_Y + 1, 0));
            mount(f, SEAT);
            f.input(LEFT);
            f.rideTick();
            f.input(NONE);
            dismount(f, removeSeat);
            pushedMove(f, COBWEB);
        }
    }

    /** The seat the first ride tick ends on is as uncertain as its vehicle's boxes, through a later vehicle snap. */
    @Test
    void theFirstRideTickKeepsItsSeatUncertaintyForTheNextRideTick() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            f.place(SEAT, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.ARMOR_STAND, SEAT, 0, 0, 0);
            PacketEntity tracked = f.player.compensatedEntities.getEntity(VEHICLE);
            float width = BoundingBoxSize.getWidth(f.player, tracked);
            float height = BoundingBoxSize.getHeight(f.player, tracked);
            SimpleCollisionBox possible = new SimpleCollisionBox(
                    0.5 - width / 2, FLOOR_Y, 0.5 - width / 2, 4.5 + width / 2, FLOOR_Y + height, 0.5 + width / 2);
            PacketEntity vehicle = new PacketEntity(f.player, VEHICLE, EntityTypeIds.ARMOR_STAND, 0.5, FLOOR_Y, 0.5) {
                @Override
                public SimpleCollisionBox getPossibleMovementCollisionBoxes(
                        SimpleCollisionBox out, SimpleCollisionBox scratch) {
                    return out.set(possible);
                }
            };
            RideTickStuckContact contact = new RideTickStuckContact();
            contact.scan(f.player, vehicle, null, false);
            Vec3 priorSeat = BoundingBoxSize.getRidingOffsetFromVehicle(
                    vehicle, f.player.compensatedEntities.getSelf(), f.player, possible);
            assertEquals(2.5, priorSeat.x);
            web(f, BlockPos.containing(4, priorSeat.y + 1, 0));
            possible.set(new SimpleCollisionBox(
                    0.5 - width / 2, FLOOR_Y, 0.5 - width / 2, 0.5 + width / 2, FLOOR_Y + height, 0.5 + width / 2));
            int contacts = contact.scan(f.player, vehicle, priorSeat, false);
            assertEquals(0b0011, contacts & 0b0011, "a seat the prior boxes allowed reaches the cobweb");
        }
    }

    /**
     * Phantom#getDefaultDimensions scales the type's 0.3375 passenger attachment by 1 + 0.15 * size, and the removed
     * phantom's rider keeps the seat Entity#positionRider gave it, less Avatar's 0.6 vehicle attachment.
     */
    @Test
    void aRemovedSizedPhantomLeavesItsRiderAtTheScaledSeat() throws Exception {
        try (var f = fixture(ClientVersion.V_26_2)) {
            Vec3 phantom = SEAT.add(0, 3, 0);
            mountPhantom(f, phantom, 3);
            f.rideTick();
            f.rideTick();
            dismount(f, true);
            PredictionResult first = f.tick(phantomSeat(phantom, 3), false);
            assertTrue(exact(first), describe(first));
        }
    }

    /**
     * The trailing proof ping follows a bundled move_entity, so its pong proves ClientPacketListener#handleMoveEntity
     * started the vehicle's three-step interpolation before the next client tick, even when that tick runs before the
     * following ping is answered: three ride ticks put the rider at the seat over the move's target.
     */
    @Test
    void aBundledVehicleMoveSeatsItsRiderAtTheTargetThreeRideTicksAfterItsProof() throws Exception {
        try (var f = fixture(ClientVersion.V_26_2)) {
            Vec3 phantom = SEAT.add(0, 3, 0);
            mountPhantom(f, phantom, 0);
            f.rideTick();
            f.rideTick();
            short dz = 4541;
            var move = new ClientboundMoveEntity(
                    VEHICLE, new EntityDelta.Linear((short) 0, (short) 0, dz), 0.0F, 0.0F, false, true, false);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, move, false);
            f.player.packetEntityReplication.onMoveEntity(event, f.player, move);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.rideTick();
            f.pongThrough(f.player.sendTransactionAndGetId());
            f.rideTick();
            f.rideTick();
            dismount(f, true);
            Vec3 target = new Vec3(phantom.x, phantom.y, phantom.z + dz / 4096.0);
            PredictionResult first = f.tick(phantomSeat(target, 0), false);
            assertTrue(exact(first), describe(first));
        }
    }

    private static void mountPhantom(JavaTickFixture f, Vec3 phantom, int size) {
        f.place(SEAT, Vec3.ZERO);
        PredictionResult standing = f.tick(SEAT, true);
        assertTrue(exact(standing), describe(standing));
        var add = new ClientboundAddEntity(
                VEHICLE, "minecraft:phantom", new Vec3d(phantom.x, phantom.y, phantom.z), 0, 0, 0);
        var addEvent = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, add, false);
        f.player.packetEntityReplication.onAddEntity(addEvent, f.player, add);
        addEvent.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        PacketEntitySizeable tracked = (PacketEntitySizeable) f.player.compensatedEntities.getEntity(VEHICLE);
        tracked.size = size;
        tracked.refreshDimensions(f.player);
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
    }

    /** Entity#positionRider for a player on a phantom of {@code size} at {@code phantom}. */
    private static Vec3 phantomSeat(Vec3 phantom, int size) {
        double attachment = (double) 0.3375F * (double) (1.0F + 0.15F * size);
        return new Vec3(phantom.x, phantom.y + attachment - 0.6, phantom.z);
    }

    /** The first free move after a {@link #LIFT} Y set_entity_motion, scaled by {@code multiplier}. */
    private static PredictionResult liftedMove(JavaTickFixture f, double multiplier) {
        f.pongThrough(f.velocity(new Vec3(0, LIFT, 0)));
        Vec3 from = new Vec3(f.player.x, f.player.y, f.player.z);
        return f.tick(from.add(0, LIFT * multiplier, 0), false);
    }

    private static JavaTickFixture fixture(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.input(NONE);
        return f;
    }

    /** A player that stood at {@code vehicle} and has ridden there for two client ticks. */
    private static JavaTickFixture riding(ClientVersion version, Vec3 vehicle) throws Exception {
        var f = fixture(version);
        ride(f, vehicle);
        return f;
    }

    private static void ride(JavaTickFixture f, Vec3 vehicle) {
        ride(f, vehicle, EntityTypeIds.ARMOR_STAND);
    }

    private static void ride(JavaTickFixture f, Vec3 vehicle, int type) {
        f.place(vehicle, Vec3.ZERO);
        PredictionResult standing = f.tick(vehicle, true);
        assertTrue(exact(standing), describe(standing));
        mount(f, vehicle, type);
        f.rideTick();
        f.rideTick();
    }

    /** One falling move into open air; the fall distance it carries. */
    private static double fall(JavaTickFixture f) {
        Vec3 above = SEAT.add(0, 1, 0);
        f.place(above, new Vec3(0, FALL, 0));
        PredictionResult falling = f.tick(above.add(0, FALL, 0), false);
        assertTrue(exact(falling), describe(falling));
        double fallDistance = carry(f).fallDistance();
        assertTrue(fallDistance > 0, "the move before the ride leaves no fall distance");
        return fallDistance;
    }

    private static void mount(JavaTickFixture f, Vec3 vehicle) {
        mount(f, vehicle, EntityTypeIds.ARMOR_STAND);
    }

    private static void mount(JavaTickFixture f, Vec3 vehicle, int type) {
        f.player.compensatedEntities.addEntity(VEHICLE, type, vehicle, 0, 0, 0);
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
    }

    /** remove_entities of the seat ends the ride as well: Entity#setRemoved makes its passengers stopRiding. */
    private static void dismount(JavaTickFixture f, boolean removeSeat) {
        List<Integer> teleports = teleportIds(f);
        if (removeSeat) removeEntity(f, VEHICLE);
        else f.setPassengers(VEHICLE, List.of());
        f.pongThrough(f.player.lastTransactionSent.get());
        acceptNewTeleports(f, teleports);
        assertFalse(f.player.compensatedEntities.getSelf().inVehicle());
    }

    /** The first free move after a 0.5 X set_entity_motion, which Entity#move scales by the carried multiplier. */
    private static void pushedMove(JavaTickFixture f, Vec3 stuckSpeed) {
        f.pongThrough(f.velocity(new Vec3(PUSH, 0, 0)));
        Vec3 from = new Vec3(f.player.x, f.player.y, f.player.z);
        double x = stuckSpeed == null ? PUSH : PUSH * stuckSpeed.x;
        PredictionResult move = f.tick(from.add(x, 0, 0), false);
        assertTrue(exact(move), describe(move));
    }

    private static void assertCarriedStuckSpeed(JavaTickFixture f, ClientVersion version, Vec3 expected) {
        if (!JavaMovementEngine.supportsExactEffects(version)) return;
        JavaPredictionCarry carry = carry(f);
        assertFalse(carry.states().isEmpty(), "the dismount carries no state");
        for (var state : carry.states()) {
            if (expected == null) assertNull(state.stuckSpeed());
            else assertEquals(expected, state.stuckSpeed());
        }
    }

    /** The carried states take no multiplier or one of {@code stuckSpeeds}, and each of those is carried. */
    private static void assertCarriedStuckCandidates(JavaTickFixture f, ClientVersion version, Vec3... stuckSpeeds) {
        if (!JavaMovementEngine.supportsExactEffects(version)) return;
        var expected = new HashSet<Vec3>(List.of(stuckSpeeds));
        expected.add(null);
        var carried = new HashSet<Vec3>();
        for (var state : carry(f).states()) carried.add(state.stuckSpeed());
        assertEquals(expected, carried);
    }

    private static JavaPredictionCarry carry(JavaTickFixture f) {
        return (JavaPredictionCarry)
                f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry();
    }

    private static void weaving(JavaTickFixture f, boolean gain) {
        if (gain) {
            var packet = new ClientboundUpdateMobEffect(f.player.entityID, "minecraft:weaving", 0);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_MOB_EFFECT, packet, false);
            f.player.packetEntityReplication.onUpdateMobEffect(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
        } else {
            var packet = new ClientboundRemoveMobEffect(f.player.entityID, "minecraft:weaving");
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_MOB_EFFECT, packet, false);
            f.player.packetEntityReplication.onRemoveMobEffect(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
        }
        f.pongThrough(f.player.lastTransactionSent.get());
        assertEquals(gain, f.player.compensatedEntities.getSelf().potionsMap.containsKey(MovementEffect.WEAVING));
    }

    private static void web(JavaTickFixture f, BlockPos pos) {
        f.player.compensatedWorld.updateBlock(pos, BlockIds.COBWEB.defaultState());
    }

    /** The block at the top of the seated player's box, which no box after the dismount reaches. */
    private static BlockPos seatTop(Vec3 seat) {
        return BlockPos.containing(seat.x, seat.y + 1.75, seat.z);
    }

    private static SimpleCollisionBox playerBox(JavaTickFixture f, Vec3 at) {
        return GetBoundingBox.getPlayerBoundingBox(f.player, at.x, at.y, at.z);
    }

    private static Vec3 rideTickSeat(JavaTickFixture f) throws Exception {
        Field field = CompensatedEntities.class.getDeclaredField("rideTickSeat");
        field.setAccessible(true);
        Vec3 seat = (Vec3) field.get(f.player.compensatedEntities);
        assertTrue(seat != null, "the last client tick ended off the vehicle");
        return seat;
    }

    private static void teleportVehicle(JavaTickFixture f, Vec3 to) {
        var packet =
                new ClientboundTeleportEntity(VEHICLE, new Vec3d(to.x, to.y, to.z), Vec3d.ZERO, 0, 0, Set.of(), false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.TELEPORT_ENTITY, packet, false);
        f.player.packetEntityReplication.onTeleportEntity(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void removeEntity(JavaTickFixture f, int id) {
        var packet = new ClientboundRemoveEntities(List.of(id));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_ENTITIES, packet, false);
        f.player.packetEntityReplication.onRemoveEntities(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    private static void acceptNewTeleports(JavaTickFixture f, List<Integer> before) {
        for (TeleportData teleport : f.player.getSetbackTeleportUtil().pendingTeleports) {
            if (before.contains(teleport.getTeleportId())) continue;
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
    }

    private static List<Integer> teleportIds(JavaTickFixture f) {
        var ids = new ArrayList<Integer>();
        for (TeleportData teleport : f.player.getSetbackTeleportUtil().pendingTeleports) {
            ids.add(teleport.getTeleportId());
        }
        return ids;
    }
}
