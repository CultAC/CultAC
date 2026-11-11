package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A respawn in the same level: ClientPacketListener#handleRespawn adds a new LocalPlayer under the old one's ID, and
 * ClientLevel#addEntity discards the old one first. Entity#setRemoved stops it riding and ejects its passengers, and
 * KEEP_ENTITY_DATA copies no riding link, so the new player rides nothing and carries nothing.
 */
class SentRidingRespawnTest {
    private static final int MINECART = 100;
    private static final int STAND = 101;

    @ParameterizedTest
    @ValueSource(
            ints = {
                0,
                ClientboundRespawn.KEEP_ATTRIBUTE_MODIFIERS,
                ClientboundRespawn.KEEP_ENTITY_DATA,
                ClientboundRespawn.KEEP_ATTRIBUTE_MODIFIERS | ClientboundRespawn.KEEP_ENTITY_DATA
            })
    void aSameLevelRespawnDropsTheOldPlayersRidingLinks(int dataToKeep) throws Exception {
        try (var f = new JavaTickFixture()) {
            add(f, MINECART, "minecraft:minecart");
            add(f, STAND, "minecraft:armor_stand");
            send(f, MINECART, List.of(f.player.entityID));
            send(f, f.player.entityID, List.of(STAND));
            f.pongThrough(f.player.lastTransactionSent.get());
            var vehicles = f.player.compensatedEntities.vehicles;
            assertSame(f.player.compensatedEntities.getEntity(MINECART), vehicles.rootVehicleAsSent());
            assertTrue(vehicles.ridesAnotherEntityAsSent(STAND));

            DeadPlayerSetbackTest.respawn(
                    f,
                    dataToKeep,
                    f.player.compensatedWorld.getLastClientboundDimension().dimension());
            assertNull(vehicles.rootVehicleAsSent());
            assertFalse(vehicles.ridesAsSent(MINECART));
            assertFalse(vehicles.ridesAnotherEntityAsSent(STAND));

            // The minecart's empty passengers, sent before the client answers the respawn, stop no rider there.
            assertFalse(send(f, MINECART, List.of()).stream().anyMatch(ClientboundPlayerPosition.class::isInstance));
        }
    }

    private static void add(JavaTickFixture f, int id, String type) {
        f.player.packetEntityReplication.addEntity(
                id, EntityTypes.defaults().byKey(type).id(), new Vec3(0.5, FLOOR_Y, 0.5), 0, 0, 0, -1);
    }

    /** The writes a set_passengers adds. */
    private static List<Object> send(JavaTickFixture f, int vehicle, List<Integer> passengers) {
        var packet = new ClientboundSetPassengers(vehicle, passengers);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        f.player.packetEntityReplication.onSetPassengers(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return event.writesAfter().stream().map(CultWrite::packet).toList();
    }
}
