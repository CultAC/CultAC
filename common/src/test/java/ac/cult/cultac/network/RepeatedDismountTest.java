package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The server can send a vehicle's empty passengers twice for one dismount, once as the player leaves it and once as its
 * tracker sees the change. ClientPacketListener#handleSetEntityPassengersPacket's Entity#ejectPassengers stops only the
 * passengers the vehicle still holds, so the second packet leaves the player wherever the first one put it.
 */
class RepeatedDismountTest {
    private static final int MINECART = 100;

    @Test
    void aRepeatedDismountSendsNoSecondResync() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.packetEntityReplication.addEntity(
                    MINECART,
                    EntityTypes.defaults().byKey("minecraft:minecart").id(),
                    new Vec3(0.5, FLOOR_Y, 0.5),
                    0,
                    0,
                    0,
                    -1);
            send(f, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            assertTrue(f.player.compensatedEntities.getSelf().inVehicle());

            List<Object> first = send(f, List.of());
            List<Object> second = send(f, List.of());
            assertTrue(has(first, ClientboundPlayerPosition.class));
            assertFalse(has(second, ClientboundPlayerPosition.class));
            assertFalse(has(second, ClientboundEntityMotion.class));

            f.pongThrough(f.player.lastTransactionSent.get());
            assertFalse(f.player.compensatedEntities.getSelf().inVehicle());
            assertFalse(f.player.compensatedEntities.vehicles.hasPendingServerDismount());
        }
    }

    /** The writes a set_passengers for the minecart adds. */
    private static List<Object> send(JavaTickFixture f, List<Integer> passengers) {
        var packet = new ClientboundSetPassengers(MINECART, passengers);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        f.player.packetEntityReplication.onSetPassengers(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return event.writesAfter().stream().map(CultWrite::packet).toList();
    }

    private static boolean has(List<Object> writes, Class<?> type) {
        return writes.stream().anyMatch(type::isInstance);
    }
}
