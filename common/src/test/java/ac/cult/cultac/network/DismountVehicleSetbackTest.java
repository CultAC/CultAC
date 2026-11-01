package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** ClientPacketListener#handleMoveVehicle ignores a vehicle move once the client has left the vehicle. */
class DismountVehicleSetbackTest {
    private static final int VEHICLE = 100;
    private static final Vec3 SAFE = new Vec3(.5, FLOOR_Y, .5);

    @Test
    void aSetbackWrittenAfterTheServerDismountMovesThePlayer() throws Exception {
        try (var f = mounted()) {
            var teleports = f.player.getSetbackTeleportUtil();
            passengers(f);
            assertTrue(f.player.compensatedEntities.vehicles.hasPendingServerDismount());
            assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
            written(f);

            assertTrue(teleports.executeViolationSetback());
            List<String> written = written(f);
            assertFalse(written.contains("minecraft:move_vehicle"), written.toString());
            assertTrue(written.contains("minecraft:player_position"), written.toString());
            assertEquals(0, teleports.queuedVehicleTeleportCount());

            acceptEveryTeleport(f);
            assertFalse(f.player.compensatedEntities.getSelf().inVehicle());
            assertFalse(teleports.isPendingSetback());
            assertFalse(teleports.shouldBlockMovement());
        }
    }

    @Test
    void aVehicleRemovedBeforeItsCorrectionIsAnsweredLeavesTheCorrectionToThePlayer() throws Exception {
        try (var f = mounted()) {
            var teleports = f.player.getSetbackTeleportUtil();
            assertTrue(teleports.executeViolationSetback());
            List<String> correction = written(f);
            assertTrue(correction.contains("minecraft:move_vehicle"), correction.toString());
            assertEquals(1, teleports.queuedVehicleTeleportCount());

            removeVehicle(f);
            List<String> removal = written(f);
            assertTrue(removal.contains("minecraft:remove_entities"), removal.toString());
            assertFalse(removal.contains("minecraft:player_position"), removal.toString());
            assertTrue(teleports.isPendingSetback());

            // The echo of the move_vehicle the client read while it still rode, then the removal's ping.
            var echo = new ServerboundMoveVehicle(new Vec3d(SAFE.x, SAFE.y, SAFE.z), 0, 0, false, false);
            f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, echo), f.player, echo);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertFalse(f.player.compensatedEntities.getSelf().inVehicle());
            List<String> written = written(f);
            assertTrue(written.contains("minecraft:player_position"), written.toString());
            acceptEveryTeleport(f);
            assertFalse(f.player.compensatedEntities.getSelf().inVehicle());
            assertFalse(teleports.isPendingSetback());
            assertFalse(teleports.shouldBlockMovement());
        }
    }

    /** A 26.3 client riding a boat at its last accepted position, with the send listeners a proxy runs. */
    private static JavaTickFixture mounted() throws Exception {
        var f = new JavaTickFixture();
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        f.player.nettyScheduler.removeScheduler();
        f.fixture.channel.runPendingTasks();
        f.fixture.registrar.register(routes -> {
            routes.send(
                    ClientboundPackets.PING,
                    PacketListenerPriority.LOW,
                    (event, player, packet) -> new PacketPingListener().onPing(event, player, packet));
            routes.send(
                    ClientboundPackets.MOVE_VEHICLE,
                    PacketListenerPriority.LOW,
                    (event, player, packet) -> new PacketServerTeleport().onMoveVehicle(event, player, packet));
            routes.send(
                    ClientboundPackets.PLAYER_POSITION,
                    PacketListenerPriority.LOW,
                    (event, player, packet) -> new PacketServerTeleport().onPlayerPosition(event, player, packet));
            routes.send(
                    ClientboundPackets.SET_PASSENGERS,
                    PacketListenerPriority.LOW,
                    (event, player, packet) -> player.packetEntityReplication.onSetPassengers(event, player, packet));
            routes.send(
                    ClientboundPackets.REMOVE_ENTITIES,
                    PacketListenerPriority.LOW,
                    (event, player, packet) ->
                            player.packetEntityReplication.onRemoveEntities(event, player, packet));
        });
        f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.OAK_BOAT, SAFE, 0, 0, 0);
        passengers(f, f.player.entityID);
        acceptEveryTeleport(f);
        assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
        f.player.getSetbackTeleportUtil().updateSafeVehiclePosition(SAFE);
        f.pongThrough(f.player.sendTrailingTransaction());
        written(f);
        return f;
    }

    /** The backend's set_passengers, as the proxy relays it. */
    private static void passengers(JavaTickFixture f, int... passengers) {
        ByteBuf frame = f.fixture.id(ClientboundPackets.SET_PASSENGERS, "set_passengers");
        Wire.writeVarInt(frame, VEHICLE);
        Wire.writeVarInt(frame, passengers.length);
        for (int passenger : passengers) Wire.writeVarInt(frame, passenger);
        f.fixture.channel.writeOutbound(frame);
    }

    /** The backend's remove_entities for the vehicle, as the proxy relays it. */
    private static void removeVehicle(JavaTickFixture f) {
        ByteBuf frame = f.fixture.id(ClientboundPackets.REMOVE_ENTITIES, "remove_entities");
        Wire.writeVarInt(frame, 1);
        Wire.writeVarInt(frame, VEHICLE);
        f.fixture.channel.writeOutbound(frame);
    }

    /** The client reads every packet in order: each teleport is accepted once the ping before it is answered. */
    private static void acceptEveryTeleport(JavaTickFixture f) {
        for (TeleportData teleport : List.copyOf(f.player.getSetbackTeleportUtil().pendingTeleports)) {
            f.pongThrough(teleport.getTransaction());
            Vec3 at = teleport.getLocation();
            var packet = new ServerboundAcceptTeleportation(teleport.getTeleportId(), new Vec3d(at.x, at.y, at.z), 0, 0);
            var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
            new PacketServerTeleport().onAcceptTeleportation(event, f.player, packet);
            assertFalse(event.isCancelled());
        }
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static List<String> written(JavaTickFixture f) {
        f.fixture.channel.flush();
        f.fixture.channel.runPendingTasks();
        var packets = f.fixture.data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND);
        var names = new ArrayList<String>();
        ByteBuf frame;
        while ((frame = f.fixture.channel.readOutbound()) != null) {
            try {
                names.add(packets.name(Wire.readVarInt(frame)));
            } finally {
                frame.release();
            }
        }
        return names;
    }
}
