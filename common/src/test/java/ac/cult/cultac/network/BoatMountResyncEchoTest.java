package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.badpackets.BadPacketsVehicle;
import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.VehicleOffset;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * ServerPlayer#startRiding sends set_passengers, and ServerEntity#sendChanges sends it again on the next server tick,
 * so CultAC resyncs the boat after each. A 26.3 client answers both resyncs' MoveVehicle from
 * ClientPacketListener#handleMoveVehicle before its next tick, here with a sneak dismount right behind the second. The
 * boat, seat and dismount spot come from issue 151's capture, moved next to the fixture's floor.
 */
class BoatMountResyncEchoTest {
    private static final int BOAT = 1487;
    private static final float BOAT_YAW = 16.72165F;
    private static final Vec3 START = new Vec3(0.7583787041054, 64.0, -0.391);
    private static final Vec3 BOAT_AT = new Vec3(0.077675659343, 64.0, 0.5480499530749);
    private static final Vec3 SEAT = new Vec3(0.077675659343, 63.5875, 0.5480499530749);
    private static final Vec3 DISMOUNT_AT = new Vec3(0.69320794376807, 64.0, -0.7242268588871);

    /** A server player_position the client accepts after its leading ping and answers its trailing ping for. */
    private record Teleport(int id, Vec3 at, int leading, int trailing) {}

    private record Passengers(List<Integer> passengers) {}

    private final List<Object> wire = new ArrayList<>();
    private Vec3 clientBoat = BOAT_AT;
    private boolean clientRiding;

    @Test
    void bothMountResyncEchoesBeforeADismountAreAccepted() throws Exception {
        try (var f = new JavaTickFixture()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.player.setExperimentalChecks(true);
            f.player.recordProvenance(true);
            f.place(START, Vec3.ZERO);
            var add = send(
                    f,
                    ClientboundPackets.ADD_ENTITY,
                    new ClientboundAddEntity(BOAT, "minecraft:oak_boat", vec3d(BOAT_AT), BOAT_YAW, 0, 0));
            f.player.packetEntityReplication.onAddEntity(add, f.player, add.getPacket());
            add.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.playerPosition(START, Vec3.ZERO, Set.of());
            f.tick(START, true);

            // ServerPlayer#startRiding.
            teleport(f, 4, SEAT);
            setPassengers(f, List.of(f.player.entityID));
            clientHandlesWire(f);
            assertTrue(f.player.inVehicle());

            // The next server tick: ServerEntity#sendChanges, then the sneak dismount and its player_position.
            setPassengers(f, List.of(f.player.entityID));
            setPassengers(f, List.of());
            teleport(f, 5, DISMOUNT_AT);
            clientHandlesWire(f);

            assertFalse(f.player.inVehicle());
            assertEquals(0, f.player.checkManager.getCheck(BadPacketsVehicle.class).violations);
            assertEquals(0, f.player.checkManager.getCheck(VehicleOffset.class).violations);
            assertEquals(0, f.player.checkManager.getCheck(OffsetHandler.class).violations);
        }
    }

    /** ClientPacketListener handles everything written so far, in order, without a client tick. */
    private void clientHandlesWire(JavaTickFixture f) {
        for (Object packet : wire) {
            if (packet instanceof ClientboundPing ping) {
                f.pongThrough(transactionOf(f, ping.id()));
            } else if (packet instanceof Teleport teleport) {
                f.pongThrough(teleport.leading());
                accept(f, teleport.id(), teleport.at());
                f.pongThrough(teleport.trailing());
            } else if (packet instanceof ClientboundPlayerPosition position) {
                accept(f, position.teleportId(), vec3(position.position()));
            } else if (packet instanceof Passengers passengers) {
                clientRiding = passengers.passengers().contains(f.player.entityID);
            } else if (packet instanceof ClientboundMoveVehicle move && clientRiding) {
                // ClientPacketListener#handleMoveVehicle snaps a target more than 1.0E-5F away, then answers.
                Vec3 target = vec3(move.position());
                if (target.distanceTo(clientBoat) > 1.0E-5F) clientBoat = target;
                f.moveVehicle(clientBoat, BOAT_YAW, 0, true);
            }
        }
        wire.clear();
    }

    private static int transactionOf(JavaTickFixture f, int wireId) {
        for (int transaction = f.player.lastTransactionSent.get(); transaction > 0; transaction--) {
            Integer id = f.player.wireIdOf(transaction);
            if (id != null && id == wireId) return transaction;
        }
        throw new IllegalStateException("no ping " + wireId);
    }

    private static void accept(JavaTickFixture f, int id, Vec3 at) {
        var accept = new ServerboundAcceptTeleportation(id, vec3d(at), BOAT_YAW, 0);
        var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, accept);
        new PacketServerTeleport().onAcceptTeleportation(event, f.player, accept);
        assertFalse(event.isCancelled(), "player_position " + id + " was not accepted");
    }

    private void setPassengers(JavaTickFixture f, List<Integer> passengers) {
        var event = send(f, ClientboundPackets.SET_PASSENGERS, new ClientboundSetPassengers(BOAT, passengers));
        f.player.packetEntityReplication.onSetPassengers(event, f.player, event.getPacket());
        wire.add(new Passengers(passengers));
        write(f, event.getWritesAfterSend());
        event.tasksAfter().forEach(Runnable::run);
    }

    /** CultEncoder: each write CultAC appends goes through CultAC's send handlers unless it is silent. */
    private void write(JavaTickFixture f, List<CultWrite> writes) {
        for (CultWrite write : List.copyOf(writes)) {
            Object packet = write.packet();
            if (write.silent()) {
                wire.add(packet);
            } else if (packet instanceof ClientboundMoveVehicle move) {
                var event = send(f, ClientboundPackets.MOVE_VEHICLE, move);
                event.authored();
                new PacketServerTeleport().onMoveVehicle(event, f.player, move);
                written(f, event, packet);
            } else if (packet instanceof ClientboundPlayerPosition position) {
                var event = send(f, ClientboundPackets.PLAYER_POSITION, position);
                event.authored();
                new PacketServerTeleport().onPlayerPosition(event, f.player, position);
                written(f, event, packet);
            } else if (packet instanceof ClientboundEntityMotion motion) {
                var event = send(f, ClientboundPackets.ENTITY_MOTION, motion);
                event.authored();
                f.player.packetEntityReplication.onSetEntityMotion(event, f.player, motion);
                written(f, event, packet);
            } else {
                wire.add(packet);
            }
        }
    }

    private void written(JavaTickFixture f, PacketSendEvent<?> event, Object packet) {
        write(f, event.getWritesBeforeSend());
        wire.add(packet);
        write(f, event.getWritesAfterSend());
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    private void teleport(JavaTickFixture f, int id, Vec3 at) {
        var teleport = new ClientboundPlayerPosition(
                id, vec3d(at), new Vec3d(0, 0, 0), 0, 0, Set.of(Relative.Y_ROT, Relative.X_ROT));
        var event = send(f, ClientboundPackets.PLAYER_POSITION, teleport);
        new PacketServerTeleport().onPlayerPosition(event, f.player, teleport);
        int leading = f.player.lastTransactionSent.get();
        event.getTasksAfterSend().forEach(Runnable::run);
        wire.add(new Teleport(id, at, leading, f.player.lastTransactionSent.get()));
    }

    private static <T extends ClientboundPacket> PacketSendEvent<T> send(
            JavaTickFixture f, PacketType<T> type, T packet) {
        return new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet, false);
    }

    private static Vec3d vec3d(Vec3 at) {
        return new Vec3d(at.x, at.y, at.z);
    }

    private static Vec3 vec3(Vec3d at) {
        return new Vec3(at.x(), at.y(), at.z());
    }
}
