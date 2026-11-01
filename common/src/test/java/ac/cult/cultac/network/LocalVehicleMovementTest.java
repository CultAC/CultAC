package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Relative moves and position syncs skip only the root vehicle the local player drives; a vehicle the player rides
 * behind another passenger, and any other entity, still follows them.
 */
class LocalVehicleMovementTest {
    private static final int DRIVEN = 60;
    private static final int OTHER = 61;
    private static final int SHARED = 62;
    private static final int DRIVER = 63;

    @Test
    void onlyTheDrivenRootIgnoresServerMovement() throws Exception {
        try (var f = new JavaTickFixture()) {
            CultPlayer player = f.player;
            f.place(new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5), Vec3.ZERO);
            add(f, DRIVEN, "minecraft:oak_boat", 2.5);
            add(f, OTHER, "minecraft:zombie", -2.5);
            add(f, SHARED, "minecraft:oak_boat", 6.5);
            add(f, DRIVER, "minecraft:zombie", 6.5);
            send(f, ClientboundPackets.SET_PASSENGERS, new ClientboundSetPassengers(DRIVEN, List.of(player.entityID)));
            pongAll(f);
            assertEquals(DRIVEN, player.compensatedEntities.vehicles.serverPlayerVehicle);
            assertMoves(f, DRIVEN, false);
            assertMoves(f, OTHER, true);

            send(f, ClientboundPackets.SET_PASSENGERS, new ClientboundSetPassengers(DRIVEN, List.of()));
            send(
                    f,
                    ClientboundPackets.SET_PASSENGERS,
                    new ClientboundSetPassengers(SHARED, List.of(DRIVER, player.entityID)));
            pongAll(f);
            assertEquals(SHARED, player.compensatedEntities.vehicles.serverPlayerVehicle);
            assertMoves(f, SHARED, true);
            assertMoves(f, DRIVEN, true);
        }
    }

    private static void assertMoves(JavaTickFixture f, int id, boolean moves) {
        for (boolean sync : new boolean[] {false, true}) {
            var entity = f.player.compensatedEntities.getEntity(id);
            String before = String.valueOf(entity.newPacketLocation.targetPosition());
            if (sync) {
                var data = f.player.compensatedEntities.getTrackedEntity(id);
                send(
                        f,
                        ClientboundPackets.ENTITY_POSITION_SYNC,
                        new ClientboundEntityPositionSync(
                                id,
                                new PositionPath.Linear(new Vec3d(data.getX() + 3, data.getY(), data.getZ())),
                                0,
                                0,
                                true));
            } else {
                send(
                        f,
                        ClientboundPackets.MOVE_ENTITY,
                        new ClientboundMoveEntity(
                                id,
                                new EntityDelta.Linear((short) 12_288, (short) 0, (short) 0),
                                0,
                                0,
                                true,
                                true,
                                false));
            }
            pongAll(f);
            String after = String.valueOf(
                    f.player.compensatedEntities.getEntity(id).newPacketLocation.targetPosition());
            String where = id + (sync ? " sync" : " move");
            if (moves) assertNotEquals(before, after, where);
            else assertEquals(before, after, where);
        }
    }

    private static void add(JavaTickFixture f, int id, String type, double x) {
        send(
                f,
                ClientboundPackets.ADD_ENTITY,
                new ClientboundAddEntity(id, type, new Vec3d(x, JavaTickFixture.FLOOR_Y, 2.5), 0, 0, 0));
        pongAll(f);
    }

    private static void pongAll(JavaTickFixture f) {
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    @SuppressWarnings("unchecked")
    private static <T extends ClientboundPacket> void send(JavaTickFixture f, PacketType<T> type, T packet) {
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet, false);
        var replication = f.player.packetEntityReplication;
        Consumer<?> handler;
        if (type == ClientboundPackets.ADD_ENTITY) {
            handler = (PacketSendEvent<ClientboundAddEntity> e) -> replication.onAddEntity(e, f.player, e.getPacket());
        } else if (type == ClientboundPackets.MOVE_ENTITY) {
            handler =
                    (PacketSendEvent<ClientboundMoveEntity> e) -> replication.onMoveEntity(e, f.player, e.getPacket());
        } else if (type == ClientboundPackets.ENTITY_POSITION_SYNC) {
            handler = (PacketSendEvent<ClientboundEntityPositionSync> e) ->
                    replication.onEntityPositionSync(e, f.player, e.getPacket());
        } else {
            handler = (PacketSendEvent<ClientboundSetPassengers> e) ->
                    replication.onSetPassengers(e, f.player, e.getPacket());
        }
        ((Consumer<PacketSendEvent<T>>) handler).accept(event);
        event.tasksAfter().forEach(Runnable::run);
    }
}
