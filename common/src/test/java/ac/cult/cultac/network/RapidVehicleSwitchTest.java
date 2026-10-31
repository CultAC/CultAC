package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsR;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsVehicle;
import ac.cult.cultac.checks.impl.crash.CrashF;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ClientPacketListener handles each mount and its MoveVehicle corrections in stream order, answering each correction
 * before its trailing ping. A later server mount cannot revoke an earlier handler response still in flight.
 */
class RapidVehicleSwitchTest {
    private static final int FIRST = 100;
    private static final int SECOND = 101;
    private static final Vec3 FIRST_POSITION = new Vec3(-3.5, JavaTickFixture.FLOOR_Y, 0.5);
    private static final Vec3 SECOND_POSITION = new Vec3(3.5, JavaTickFixture.FLOOR_Y, 0.5);

    @ParameterizedTest
    @ValueSource(doubles = {3.5, 16.5})
    void queuedMountResponsesSurviveSendingTheNextMount(double distance) throws Exception {
        try (var f = fixture(distance)) {
            var firstMoves = mount(f, FIRST);
            int firstProof = f.player.lastTransactionSent.get();
            int firstResponses = f.player.getSetbackTeleportUtil().queuedVehicleTeleportCount();
            assertTrue(firstResponses > 0);

            var secondMoves = mount(f, SECOND);
            assertTrue(f.player.getSetbackTeleportUtil().queuedVehicleTeleportCount() > firstResponses);

            for (var move : firstMoves) echo(f, move);
            f.pongThrough(firstProof);
            assertEquals(
                    FIRST, f.player.compensatedEntities.getSelf().getRiding().getEntityId());

            // SECOND's packet handler responds before its pong updates the compensated passenger graph.
            for (var move : secondMoves) echo(f, move);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(
                    SECOND, f.player.compensatedEntities.getSelf().getRiding().getEntityId());
            assertEquals(0, f.player.getSetbackTeleportUtil().queuedVehicleTeleportCount());
            assertEquals(0, f.player.checkManager.getCheck(BadPacketsVehicle.class).violations);
            assertEquals(0, f.player.checkManager.getCheck(CrashF.class).violations);
        }
    }

    @Test
    void aPendingPassiveMountDoesNotReplaceTheClientsCurrentMovementRoot() throws Exception {
        try (var f = fixture();
                var ignored = RecordReceiveFixture.observeOnly(true)) {
            f.setPassengers(FIRST, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            var current = f.player.compensatedEntities.getEntity(FIRST);

            // The server has changed to a minecart, but the client still controls FIRST until the mount's proof.
            int minecart = EntityTypes.defaults().byKey("minecraft:minecart").id();
            f.player.compensatedEntities.addEntity(SECOND + 1, minecart, SECOND_POSITION, 0, 0, 0);
            f.setPassengers(SECOND + 1, List.of(f.player.entityID));
            assertSame(current, f.player.compensatedEntities.vehicles.getVelocityMovementVehicle());
            assertTrue(JavaTickFixture.exact(f.rideTick(FIRST_POSITION, true)));
            assertEquals(0, f.player.checkManager.getCheck(BadPacketsVehicle.class).violations);

            // Withholding the old vehicle from the server must still let BadPacketsR observe its valid update.
            var starvation = f.player.checkManager.getCheck(BadPacketsR.class);
            JavaTickFixture.setField(BadPacketsR.class, starvation, "clock", System.nanoTime() - 5_000_000_000L);
            JavaTickFixture.setField(BadPacketsR.class, starvation, "lastTransTime", 0L);
            var pong = new ServerboundPong(17);
            var event = f.event(ServerboundPackets.PONG, pong);
            event.setAcceptedTransactionResponse(true);
            starvation.onPong(event, f.player, pong);
            assertEquals(0, starvation.violations);
        }
    }

    private static JavaTickFixture fixture() throws Exception {
        return fixture(3.5);
    }

    private static JavaTickFixture fixture(double distance) throws Exception {
        var f = new JavaTickFixture();
        var first = new Vec3(-distance, JavaTickFixture.FLOOR_Y, 0.5);
        var second = new Vec3(distance, JavaTickFixture.FLOOR_Y, 0.5);
        f.place(first, Vec3.ZERO);
        f.player.setExperimentalChecks(true);
        add(f, FIRST, first);
        add(f, SECOND, second);
        return f;
    }

    private static void add(JavaTickFixture f, int id, Vec3 position) {
        f.player.compensatedEntities.addEntity(id, EntityTypeIds.OAK_BOAT, position, 0, 0, 0);
        f.player.compensatedEntities.serverPositionsMap.put(
                id, new TrackerData(position.x, position.y, position.z, 0, 0, EntityTypeIds.OAK_BOAT, 0));
    }

    private static List<ClientboundMoveVehicle> mount(JavaTickFixture f, int vehicle) {
        var packet = new ClientboundSetPassengers(vehicle, List.of(f.player.entityID));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        f.player.packetEntityReplication.onSetPassengers(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return event.writesAfter().stream()
                .map(CultWrite::packet)
                .filter(ClientboundMoveVehicle.class::isInstance)
                .map(ClientboundMoveVehicle.class::cast)
                .toList();
    }

    private static void echo(JavaTickFixture f, ClientboundMoveVehicle move) {
        var packet = new ServerboundMoveVehicle(move.position(), move.yaw(), move.pitch(), true, true);
        var event = f.event(ServerboundPackets.MOVE_VEHICLE, packet);
        double before = f.player.checkManager.getCheck(BadPacketsVehicle.class).violations;
        f.listener.onMoveVehicle(event, f.player, packet);
        assertEquals(before, f.player.checkManager.getCheck(BadPacketsVehicle.class).violations);
    }
}
