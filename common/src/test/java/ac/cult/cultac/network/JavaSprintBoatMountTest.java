package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.sprint.SprintG;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPaddleBoat;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A 1.21.4 client sprints up to a boat floating in water and rides it. ServerPlayer#startRiding teleports the player
 * to the seat, and on the first ride tick LocalPlayer#aiStep stops the passenger's sprint (shouldStopSprinting) before
 * LocalPlayer#tick sends the vehicle move, then the STOP_SPRINTING. Positions and the boat's set_entity_motion come
 * from the client's capture, moved next to the fixture's floor.
 */
class JavaSprintBoatMountTest {
    private static final int BOAT = 979;
    private static final int OTHER_BOAT = 980;
    private static final int CAMEL = 981;
    private static final float BOAT_YAW = -158.7921F;
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final Vec3 BOAT_SYNC = at(240.39493855367655, 61.535082140448594, 204.5464472442114);
    private static final Vec3 FIRST_RIDE = at(240.39493855367655, 61.53411247253438, 204.5464472442114);
    private static final Vec3 SECOND_RIDE = at(240.4094094633987, 61.5323786171157, 204.5091565928602);
    private static final Vec3 OTHER_BOAT_OFFSET = new Vec3(1.5, 0, 0);

    @Test
    void sprintingIntoABoatOnWaterIsNotSprintG() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_4)) {
            SprintG sprintG = mount(f);

            // AbstractBoat#tick runs before the passenger's first Entity#rideTick sets its paddles.
            PredictionResult firstRide = firstRideTick(f, true);
            assertTrue(exact(firstRide), "first ride tick: " + describe(firstRide));
            assertEquals(0.0, sprintG.violations, "SprintG on the first ride tick");
            PredictionResult secondRide = secondRideTick(f);
            assertTrue(exact(secondRide), "second ride tick: " + describe(secondRide));
            assertEquals(0.0, sprintG.violations, "SprintG on the second ride tick");
        }
    }

    /** A client that sends no STOP_SPRINTING after the first ride tick still sprints on the second. */
    @Test
    void aPassengerThatKeepsSprintingInABoatOnWaterIsSprintG() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_4)) {
            SprintG sprintG = mount(f);

            PredictionResult firstRide = firstRideTick(f, false);
            assertTrue(exact(firstRide), "first ride tick: " + describe(firstRide));
            assertEquals(0.0, sprintG.violations, "SprintG on the first ride tick");
            PredictionResult secondRide = secondRideTick(f);
            assertTrue(exact(secondRide), "second ride tick: " + describe(secondRide));
            assertTrue(sprintG.violations > 0, "SprintG on the second ride tick");
        }
    }

    /**
     * A 1.21.2 LocalPlayer#aiStep stops the sprint of a boat passenger, whom the boat keeps out of the water, only
     * without forward impulse or on a horizontal collision, so a passenger paddling forward keeps it and sends no
     * STOP_SPRINTING.
     */
    @Test
    void a1_21_2PassengerPaddlingForwardKeepsItsSprint() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_2)) {
            SprintG sprintG = mount(f);

            PredictionResult firstRide = firstRideTick(f, false);
            assertTrue(exact(firstRide), "first ride tick: " + describe(firstRide));
            PredictionResult secondRide = secondRideTick(f);
            assertTrue(exact(secondRide), "second ride tick: " + describe(secondRide));
            assertEquals(0.0, sprintG.violations, "SprintG");
        }
    }

    /**
     * A passenger that moves from one boat to another held no sprint on the first, so its first move on the second
     * carries the stopped flag.
     */
    @Test
    void aPassengerSwitchingBoatsIsJudgedOnItsFirstMoveInTheSecond() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_4)) {
            SprintG sprintG = mount(f);
            firstRideTick(f, true);
            secondRideTick(f);
            assertEquals(0.0, sprintG.violations, "SprintG in the first boat");

            PredictionResult switched = switchBoats(f);
            assertTrue(exact(switched), "first move in the second boat: " + describe(switched));
            assertEquals(0.0, sprintG.violations, "SprintG in the second boat");
        }
    }

    @Test
    void aPassengerThatStartsSprintingAndSwitchesBoatsIsSprintG() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_4)) {
            SprintG sprintG = mount(f);
            firstRideTick(f, true);
            secondRideTick(f);
            command(f, PlayerCommandAction.START_SPRINTING);
            assertEquals(0.0, sprintG.violations, "SprintG in the first boat");

            PredictionResult switched = switchBoats(f);
            assertTrue(exact(switched), "first move in the second boat: " + describe(switched));
            assertTrue(sprintG.violations > 0, "SprintG in the second boat");
        }
    }

    /**
     * Camel#canSprint lets the camel's controlling passenger keep its sprint, so the first move in a boat after it
     * still carries that sprint and the STOP_SPRINTING after it holds for the next.
     */
    @Test
    void aSprintingCamelRiderSwitchingToABoatStopsAfterItsFirstMove() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_4)) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.player.setExperimentalChecks(true);
            SprintG sprintG = f.player.checkManager.getCheck(SprintG.class);
            sprintG.violations = 0;
            f.water(new BlockPos(-3, FLOOR_Y - 1, -3), new BlockPos(3, FLOOR_Y - 1, 0));
            Vec3 camelAt = new Vec3(0.5, FLOOR_Y, 3.5);
            f.place(camelAt.add(0, 1, 0), Vec3.ZERO);
            f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, camelAt, 0, 0, 0);
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(CAMEL)).hasSaddle = true;
            Vec3 boat = at(240.39491855367655, 61.535082140448594, 204.5464472442114);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, boat, 0, BOAT_YAW, 0);
            f.setPassengers(CAMEL, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.player.isSprinting = true;
            for (int tick = 0; tick < 2; tick++) {
                PredictionResult camelRide = f.rideTick(camelAt, true);
                assertTrue(exact(camelRide), "camel ride tick " + tick + ": " + describe(camelRide));
            }
            f.input(FORWARD_SPRINT);

            f.setPassengers(CAMEL, List.of());
            enter(f, BOAT, 2, at(240.39491855367655, 61.123034541455986, 204.5464472442114), BOAT_SYNC);
            PredictionResult firstRide = firstRideTick(f, true);
            assertTrue(exact(firstRide), "first ride tick: " + describe(firstRide));
            assertEquals(0.0, sprintG.violations, "SprintG on the first ride tick");
            PredictionResult secondRide = secondRideTick(f);
            assertTrue(exact(secondRide), "second ride tick: " + describe(secondRide));
            assertEquals(0.0, sprintG.violations, "SprintG on the second ride tick");
        }
    }

    /** The sprinting player's last walking tick, the server's teleport to the seat and the vehicle's echo. */
    private static SprintG mount(JavaTickFixture f) {
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        f.player.setExperimentalChecks(true);
        SprintG sprintG = f.player.checkManager.getCheck(SprintG.class);
        sprintG.violations = 0;
        f.water(new BlockPos(-3, FLOOR_Y - 1, -3), new BlockPos(3, FLOOR_Y - 1, 0));

        Vec3 previous = at(239.8060471917903, 62.0, 206.84865279245452);
        Vec3 start = at(239.87951034577154, 62.0, 206.5786578665251);
        double friction = 0.6F * 0.91F;
        f.place(
                start,
                new Vec3((start.x - previous.x) * friction, -0.0784000015258789, (start.z - previous.z) * friction));
        f.player.xRot = f.player.lastTickXRot = 195.13289F;
        f.player.yRot = f.player.lastTickYRot = 39.038296F;
        f.player.isSprinting = true;
        f.player.sprintState.onClientTick(true);
        f.player.sprintState.onClientTickEnd();
        f.input(FORWARD_SPRINT);
        Vec3 boat = at(240.39491855367655, 61.535082140448594, 204.5464472442114);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, boat, 0, BOAT_YAW, 0);
        f.player.compensatedEntities.addEntity(
                OTHER_BOAT, EntityTypeIds.OAK_BOAT, boat.add(OTHER_BOAT_OFFSET), 0, BOAT_YAW, 0);
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = 194.90788F;
        f.player.yRot = 41.457066F;
        PredictionResult walk = f.tick(at(239.95238630249239, 62.0, 206.30812598280386), true);
        assertTrue(exact(walk), "walk: " + describe(walk));

        Vec3 seat = at(240.39491855367655, 61.123034541455986, 204.5464472442114);
        enter(f, BOAT, 2, seat, BOAT_SYNC);
        return sprintG;
    }

    /**
     * ServerPlayer#startRiding's teleport, set_passengers and move_vehicle; ClientPacketListener#handleMovePlayer
     * answers with the ID, then a PosRot at the seat, and handleMoveVehicle echoes the vehicle's position.
     */
    private static void enter(JavaTickFixture f, int boat, int teleportId, Vec3 seat, Vec3 boatAt) {
        int trailing = f.startRiding(boat, teleportId, seat, boatAt, BOAT_YAW, 0.0F);
        f.pongThrough(trailing - 1);
        f.acceptTeleport(teleportId);
        var posRot =
                new ServerboundMovePlayer(seat.x, seat.y, seat.z, 194.34538F, 42.638325F, false, false, true, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, posRot), f.player, posRot);
        f.pongThrough(trailing);
        f.moveVehicle(boatAt, BOAT_YAW, 0, false);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static PredictionResult firstRideTick(JavaTickFixture f, boolean stop) {
        return rideTick(f, false, -158.96085F, 42.975826F, FIRST_RIDE, stop);
    }

    /** The boat's set_entity_motion from the server's tick, then the second ride tick paddling forward. */
    private static PredictionResult secondRideTick(JavaTickFixture f) {
        f.entityMotion(BOAT, new Vec3(0.0, -0.001125, 0.0));
        f.pongThrough(f.player.lastTransactionSent.get());
        return rideTick(f, true, -159.63585F, 43.93208F, SECOND_RIDE, false);
    }

    /** The other boat, at rest beside the first, takes the passenger and makes the first boat's first ride move. */
    private static PredictionResult switchBoats(JavaTickFixture f) {
        f.setPassengers(BOAT, List.of());
        Vec3 seat = at(240.39491855367655, 61.123034541455986, 204.5464472442114).add(OTHER_BOAT_OFFSET);
        enter(f, OTHER_BOAT, 3, seat, BOAT_SYNC.add(OTHER_BOAT_OFFSET));
        return rideTick(f, false, -158.96085F, 42.975826F, FIRST_RIDE.add(OTHER_BOAT_OFFSET), true);
    }

    /** A ride tick: paddles, the rider's Rot, the boat's move, then STOP_SPRINTING when {@code stop}. */
    private static PredictionResult rideTick(
            JavaTickFixture f, boolean paddling, float yaw, float pitch, Vec3 boat, boolean stop) {
        var paddle = new ServerboundPaddleBoat(paddling, paddling);
        f.listener.onPaddleBoat(f.event(ServerboundPackets.PADDLE_BOAT, paddle), f.player, paddle);
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        f.moveVehicle(boat, BOAT_YAW, 0, false);
        if (stop) command(f, PlayerCommandAction.STOP_SPRINTING);
        f.tickEnd();
        return f.player.checkManager.getSimulationProcessor().getLastPrediction();
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action) {
        var command = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        new PacketEntityAction()
                .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
    }

    private static Vec3 at(double x, double y, double z) {
        return new Vec3(x - 240, y + 2, z - 205);
    }
}
