package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.observeOnly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPaddleBoat;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Only AbstractBoat#setInput writes a boat's keys, and LocalPlayer#rideTick calls it after the boat's own tick. A 26.1
 * client riding a boat again moves it on the first ride tick by the keys the boat kept from its last ride, not by the
 * keys the player walked with. Positions come from the capture, moved next to the fixture's floor.
 */
class BoatRemountInputTest {
    private static final int BOAT = 22;
    private static final int OTHER_BOAT = 23;
    private static final float BOAT_YAW = -76.57969F;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, true);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final Vec3 REST = at(241.08265814773083, 61.52326431274414, 176.78183424914727);
    private static final Vec3 SEAT = at(241.08263814773082, 61.11076431274414, 176.78183424914727);
    private static final Vec3 OTHER_REST = REST.add(2, 0, 0);
    private static final Vec3 OTHER_SEAT = SEAT.add(2, 0, 0);
    /** The capture's first ride move: AbstractBoat#controlBoat's 0.04 forward along the boat's yaw. */
    private static final Vec3 PADDLED = at(241.12156508705854, 61.52326431274414, 176.79111761110374);

    @Test
    void aBoatRiddenAgainMovesByTheKeysOfItsLastRide() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_1, ClientVersion.V_26_1);
                var ignored = observeOnly()) {
            ride(f, FORWARD, 1);
            walk(f, NONE);
            ride(f, null, 2);
            assertVehicleExact(rideTick(f, true, PADDLED), 0);
        }
    }

    @Test
    void aBoatRiddenAgainStaysWithoutKeysFromItsLastRide() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_1, ClientVersion.V_26_1);
                var ignored = observeOnly()) {
            ride(f, NONE, 1);
            walk(f, FORWARD);
            ride(f, null, 2);
            assertVehicleExact(rideTick(f, false, REST), 0);
        }
    }

    @Test
    void aBoatRiddenAgainMovingWithoutKeysFromItsLastRideIsVehicleOffset() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_1, ClientVersion.V_26_1);
                var ignored = observeOnly()) {
            ride(f, NONE, 1);
            walk(f, FORWARD);
            ride(f, null, 2);
            PredictionResult paddled = rideTick(f, true, PADDLED);
            assertTrue(
                    paddled.getFlags().stream()
                            .anyMatch(flag -> flag.getCheck().getCheckName().equals("VehicleOffset")),
                    describe(paddled));
        }
    }

    @Test
    void aBoatLeftForAnotherBeforeTheClientMountsItKeepsTheKeysOfItsLastRide() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_1, ClientVersion.V_26_1);
                var ignored = observeOnly()) {
            ride(f, NONE, 1);
            f.player.compensatedEntities.addEntity(OTHER_BOAT, EntityTypeIds.OAK_BOAT, OTHER_REST, 0, BOAT_YAW, 0);
            int trailing = f.startRiding(OTHER_BOAT, 2, OTHER_SEAT, OTHER_REST, BOAT_YAW, 0.0F);
            f.input(FORWARD);
            rideTick(f, false, REST);
            board(f, trailing, 2, OTHER_SEAT, OTHER_REST);
            ride(f, null, 3);
            assertVehicleExact(rideTick(f, true, PADDLED), 0);
        }
    }

    /**
     * ServerPlayer#startRiding's teleport to the seat, set_passengers and move_vehicle, the client's answers, then one
     * ride tick at rest with {@code input} sent at its end, when not null.
     */
    private static void ride(JavaTickFixture f, ServerboundPlayerInput input, int teleportId) {
        if (teleportId == 1) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.water(new BlockPos(-3, FLOOR_Y - 1, -3), new BlockPos(3, FLOOR_Y - 1, 3));
            f.place(SEAT.add(1.5, 0.5, 1.5), Vec3.ZERO);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, REST, 0, BOAT_YAW, 0);
        }
        board(f, f.startRiding(BOAT, teleportId, SEAT, REST, BOAT_YAW, 0.0F), teleportId, SEAT, REST);
        if (input != null) {
            f.input(input);
            assertVehicleExact(rideTick(f, false, REST), 0);
        }
    }

    /** The client's answers to startRiding with the ping {@code trailing} its teleport: accept, PosRot and the echo. */
    private static void board(JavaTickFixture f, int trailing, int teleportId, Vec3 seat, Vec3 rest) {
        f.pongThrough(trailing - 1);
        f.acceptTeleport(teleportId);
        var posRot = new ServerboundMovePlayer(seat.x, seat.y, seat.z, BOAT_YAW, 60.0F, false, false, true, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, posRot), f.player, posRot);
        f.pongThrough(trailing);
        f.moveVehicle(rest, BOAT_YAW, 0, false);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** The server's dismount, then one walking client tick sending {@code input}. */
    private static void walk(JavaTickFixture f, ServerboundPlayerInput input) {
        f.setPassengers(BOAT, List.of());
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(input);
        f.move(new Vec3(f.player.x, f.player.y, f.player.z), false);
        f.tickEnd();
    }

    /** The paddles, the rider's Rot, the boat's move to {@code to} and the tick end. */
    private static PredictionResult rideTick(JavaTickFixture f, boolean paddling, Vec3 to) {
        var paddle = new ServerboundPaddleBoat(paddling, paddling);
        f.listener.onPaddleBoat(f.event(ServerboundPackets.PADDLE_BOAT, paddle), f.player, paddle);
        var rot = new ServerboundMovePlayer(0, 0, 0, BOAT_YAW, 60.0F, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        f.moveVehicle(to, BOAT_YAW, 0, false);
        f.tickEnd();
        return f.player.checkManager.getSimulationProcessor().getLastPrediction();
    }

    private static Vec3 at(double x, double y, double z) {
        return new Vec3(x - 241, y + 2, z - 177);
    }
}
