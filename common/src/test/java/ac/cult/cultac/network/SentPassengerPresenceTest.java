package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ClientPacketListener#handleSetEntityPassengersPacket ignores a vehicle the client does not hold and starts only the
 * listed passengers it holds riding. ClientLevel#addEntity first discards the entity holding the new entity's ID, which
 * stops it riding and ejects its passengers. A riding link the client never forms must not make the server's
 * ServerGamePacketListenerImpl#handleMoveVehicle look like it ignores the player's boat.
 */
class SentPassengerPresenceTest {
    private static final int BOAT = 100;
    private static final int STAND = 300;
    private static final int ABSENT = 9999;
    private static final Vec3 AT = new Vec3(0.5, FLOOR_Y, 0.5);

    @Test
    void aSetPassengersForAVehicleTheClientDoesNotHoldChangesNoRide() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity boat = board(f);

            f.setPassengers(ABSENT, List.of(f.player.entityID));

            assertEquals(BOAT, f.player.compensatedEntities.vehicles.serverPlayerVehicle);
            assertTrue(f.player.compensatedEntities.vehicles.serverMovesVehicleFor(boat));
            assertSame(boat, f.player.compensatedEntities.vehicles.rootVehicleAsSent());
        }
    }

    @Test
    void aVehicleWhoseRemovalWasSentTakesNoPassengers() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity boat = board(f);
            spawn(f, STAND, "minecraft:armor_stand");
            remove(f, STAND);

            f.setPassengers(STAND, List.of(BOAT));

            assertFalse(f.player.compensatedEntities.vehicles.ridesAnotherEntityAsSent(BOAT));
            assertTrue(f.player.compensatedEntities.vehicles.serverMovesVehicleFor(boat));
        }
    }

    @Test
    void aListedPassengerTheClientDoesNotHoldIsNotLinkedWhenItSpawnsLater() throws Exception {
        try (var f = new JavaTickFixture()) {
            board(f);
            spawn(f, STAND, "minecraft:armor_stand");

            f.setPassengers(STAND, List.of(ABSENT));
            spawn(f, ABSENT, "minecraft:oak_boat");

            assertFalse(f.player.compensatedEntities.vehicles.ridesAnotherEntityAsSent(ABSENT));
        }
    }

    @Test
    void aSpawnReusingAVehiclesIdEjectsItsPassengers() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity boat = board(f);
            spawn(f, STAND, "minecraft:armor_stand");
            f.setPassengers(STAND, List.of(BOAT));
            f.pongThrough(f.player.lastTransactionSent.get());
            assertTrue(f.player.compensatedEntities.vehicles.ridesAnotherEntityAsSent(BOAT));
            assertFalse(f.player.compensatedEntities.vehicles.serverMovesVehicleFor(boat));

            spawn(f, STAND, "minecraft:armor_stand");

            assertFalse(f.player.compensatedEntities.vehicles.ridesAnotherEntityAsSent(BOAT));
            assertTrue(f.player.compensatedEntities.vehicles.serverMovesVehicleFor(boat));
            // The client-side link is discarded with the replaced stand, once the client handles the spawn.
            assertNull(boat.getRiding());
        }
    }

    /** The player aboard a boat the client holds, as answered. */
    private static PacketEntity board(JavaTickFixture f) throws Exception {
        f.place(AT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, AT, 0, 0, 0);
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        PacketEntity boat = f.player.compensatedEntities.getEntity(BOAT);
        assertSame(boat, f.player.compensatedEntities.getSelf().getRiding());
        assertTrue(f.player.compensatedEntities.vehicles.serverMovesVehicleFor(boat));
        return boat;
    }

    private static void spawn(JavaTickFixture f, int id, String type) {
        var packet = new ClientboundAddEntity(
                id, type, new Vec3d(AT.x + 3, AT.y, AT.z), 0, 0, 0, new UUID(0, id), new Vec3d(0, 0, 0));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, packet, false);
        f.player.packetEntityReplication.onAddEntity(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void remove(JavaTickFixture f, int id) {
        var packet = new ClientboundRemoveEntities(List.of(id));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_ENTITIES, packet, false);
        f.player.packetEntityReplication.onRemoveEntities(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }
}
