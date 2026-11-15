package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.checks.impl.badpackets.BadPacketsVehicle;
import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoFallPseudo;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.VehicleOffset;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A plugin's addPassenger mounts an on-foot 26.3 client on a saddled horse: ServerPlayer#startRiding sends the
 * player_position to the seat, then the set_passengers, to which CultAC appends the mount resync (two move_vehicle and
 * the horse's set_entity_motion) and the proof the mount takes effect at. ClientPacketListener#handleBundlePacket
 * handles a bundle in one task, and Minecraft#runTick handles queued packets only before its client ticks, so the
 * client can tick wherever the wire leaves a bundle. Here it ticks at the last such point before the proof, as the
 * capture's client did after answering both resync moves: LocalPlayer#sendChanges sends the rider's Rot and the
 * horse's MoveVehicle, then the tick ends. Positions come from the capture, moved next to the fixture's floor.
 */
class HorseRemountTickBeforeProofTest {
    private static final int HORSE = 20739;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 4.5);
    private static final Vec3 HORSE_AT = new Vec3(0.5, FLOOR_Y, 0.5);
    /** The seat ServerPlayer#startRiding teleports the rider to: the horse's passenger attachment. */
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y + 0.84375002384186, 0.5);

    private sealed interface Frame permits Delimiter, Ping, Passengers, MoveVehicle, Other {}

    private record Delimiter() implements Frame {}

    private record Ping(int id) implements Frame {}

    private record Passengers(int vehicle, List<Integer> passengers) implements Frame {}

    private record MoveVehicle(Vec3 at, float yaw, float pitch) implements Frame {}

    private record Other(String name) implements Frame {}

    private Vec3 clientHorse = HORSE_AT;
    private boolean clientRiding;

    @Test
    void theMountResyncAndItsProofReachTheClientInOneBundleTask() throws Exception {
        try (var f = new JavaTickFixture()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(mock(PlatformWorld.class));
            f.player.setExperimentalChecks(true);
            f.player.recordProvenance(true);
            routes(f.fixture);
            f.place(START, Vec3.ZERO);
            var add = new PacketSendEvent<>(
                    f.fixture.user,
                    ConnectionPhase.PLAY,
                    ClientboundPackets.ADD_ENTITY,
                    new ClientboundAddEntity(HORSE, "minecraft:horse", JavaTickFixture.vec3d(HORSE_AT), 0, 0, 0),
                    false);
            f.player.packetEntityReplication.onAddEntity(add, f.player, add.getPacket());
            add.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
            horse.hasSaddle = true;
            horse.isTame = true;
            horse.onGround = true;
            // The client has the chunk it stands in once it answers a ping sent after it.
            f.pongThrough(f.player.sendTrailingTransaction());
            f.playerPosition(START, Vec3.ZERO, Set.of());
            f.place(START, Vec3.ZERO);
            for (int i = 0; i < 3; i++) f.tick(START, true);

            // ServerPlayer#startRiding's player_position, handled and accepted before the set_passengers.
            seatTeleport(f, 3282);
            drain(f.fixture);

            // Its set_passengers, as the server wrote it, with no CultAC bundle open.
            ByteBuf mount = f.fixture.id(ClientboundPackets.SET_PASSENGERS, "set_passengers");
            Wire.writeVarInt(mount, HORSE);
            Wire.writeVarInt(mount, 1);
            Wire.writeVarInt(mount, f.player.entityID);
            var written = f.fixture.channel.write(mount);
            assertFalse(written.isDone() && !written.isSuccess(), () -> String.valueOf(written.cause()));
            List<Frame> frames = drain(f.fixture);

            int mountIndex = -1;
            int lastPing = -1;
            for (int i = 0; i < frames.size(); i++) {
                if (frames.get(i) instanceof Passengers && mountIndex < 0) mountIndex = i;
                if (frames.get(i) instanceof Ping) lastPing = i;
            }
            assertTrue(mountIndex >= 0 && lastPing > mountIndex, "wire " + frames);
            // The last point outside a bundle after the set_passengers and before the proof; none when one bundle
            // holds them both.
            int tickAt = frames.size();
            boolean inside = false;
            for (int i = 0; i <= lastPing; i++) {
                if (!inside && i > mountIndex) tickAt = i;
                if (frames.get(i) instanceof Delimiter) inside = !inside;
            }

            for (int i = 0; i < frames.size(); i++) {
                if (i == tickAt) firstRideTick(f);
                handle(f, frames.get(i));
            }
            if (tickAt == frames.size()) firstRideTick(f);

            assertTrue(f.player.inVehicle());
            assertNoFlags(f, "the first ride tick, wire " + frames);
            // The horse fell nowhere in its first tick, so Entity#move finds the floor only in the next.
            for (int i = 0; i < 4; i++) f.rideTick(clientHorse, true);
            assertNoFlags(f, "the ride after it");
        }
    }

    private static void assertNoFlags(JavaTickFixture f, String when) {
        var checks = f.player.checkManager;
        assertEquals(0, checks.getCheck(OffsetHandler.class).violations, "Simulation on " + when);
        assertEquals(0, checks.getCheck(NoFallPseudo.class).violations, "GroundSpoof on " + when);
        assertEquals(0, checks.getCheck(BadPacketsVehicle.class).violations, "BadPacketsVehicle on " + when);
        assertEquals(0, checks.getCheck(VehicleOffset.class).violations, "VehicleOffset on " + when);
    }

    /**
     * LocalPlayer#sendChanges as a rider of the horse it controls: the Rot, then the MoveVehicle. The resync left the
     * horse at rest, so its first LivingEntity#travel moves it nowhere and Entity#move reports it off the ground.
     */
    private void firstRideTick(JavaTickFixture f) {
        var rot = new ServerboundMovePlayer(0, 0, 0, 0, 0, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        f.moveVehicle(clientHorse, 0, 0, false);
        f.tickEnd();
    }

    /** ClientPacketListener handling one frame. */
    private void handle(JavaTickFixture f, Frame frame) {
        switch (frame) {
            case Ping ping -> f.pongThrough(transactionOf(f, ping.id()));
            case Passengers passengers -> clientRiding = passengers.passengers().contains(f.player.entityID);
            case MoveVehicle move -> {
                if (!clientRiding) return;
                // ClientPacketListener#handleMoveVehicle snaps a target more than 1.0E-5F away, then answers.
                if (move.at().distanceTo(clientHorse) > 1.0E-5F) clientHorse = move.at();
                f.moveVehicle(clientHorse, move.yaw(), move.pitch(), true);
            }
            default -> {}
        }
    }

    /** ServerPlayer#startRiding's player_position {@code id} to the seat, accepted after its leading ping. */
    private static void seatTeleport(JavaTickFixture f, int id) {
        var teleport = new ClientboundPlayerPosition(
                id, JavaTickFixture.vec3d(SEAT), new Vec3d(0, 0, 0), 0, 0, Set.of(Relative.Y_ROT, Relative.X_ROT));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_POSITION, teleport, false);
        new PacketServerTeleport().onPlayerPosition(event, f.player, teleport);
        int leading = f.player.lastTransactionSent.get();
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(leading);
        var accept = new ServerboundAcceptTeleportation(id, JavaTickFixture.vec3d(SEAT), 0, 0);
        var acceptEvent = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, accept);
        new PacketServerTeleport().onAcceptTeleportation(acceptEvent, f.player, accept);
        assertFalse(acceptEvent.isCancelled(), "the seat teleport was not accepted");
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static int transactionOf(JavaTickFixture f, int wireId) {
        for (int transaction = f.player.lastTransactionSent.get(); transaction > 0; transaction--) {
            Integer id = f.player.wireIdOf(transaction);
            if (id != null && id == wireId) return transaction;
        }
        throw new IllegalStateException("no ping " + wireId);
    }

    /** Flushes the channel, closing any flush bundle, and decodes every frame written to the client. */
    private static List<Frame> drain(RecordReceiveFixture f) {
        f.channel.flush();
        f.channel.runPendingTasks();
        var packets = f.data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND);
        var frames = new ArrayList<Frame>();
        ByteBuf frame;
        while ((frame = f.channel.readOutbound()) != null) {
            try {
                String name = packets.name(Wire.readVarInt(frame));
                frames.add(
                        switch (name) {
                            case "minecraft:bundle_delimiter" -> new Delimiter();
                            case "minecraft:ping" -> new Ping(frame.readInt());
                            case "minecraft:set_passengers" -> {
                                int vehicle = Wire.readVarInt(frame);
                                int count = Wire.readVarInt(frame);
                                var passengers = new ArrayList<Integer>(count);
                                for (int i = 0; i < count; i++) passengers.add(Wire.readVarInt(frame));
                                yield new Passengers(vehicle, passengers);
                            }
                            case "minecraft:move_vehicle" ->
                                new MoveVehicle(
                                        new Vec3(frame.readDouble(), frame.readDouble(), frame.readDouble()),
                                        frame.readFloat(),
                                        frame.readFloat());
                            default -> new Other(name);
                        });
            } finally {
                frame.release();
            }
        }
        return frames;
    }

    private static void routes(RecordReceiveFixture f) {
        f.player.nettyScheduler.removeScheduler();
        f.channel.runPendingTasks();
        f.registrar.register(routes -> {
            routes.send(
                    ClientboundPackets.PING,
                    PacketListenerPriority.LOW,
                    (event, player, packet) -> new PacketPingListener().onPing(event, player, packet));
            routes.send(
                    ClientboundPackets.ENTITY_MOTION,
                    PacketListenerPriority.LOW,
                    (event, player, packet) -> player.packetEntityReplication.onSetEntityMotion(event, player, packet));
            routes.send(
                    ClientboundPackets.MOVE_VEHICLE,
                    PacketListenerPriority.LOW,
                    (event, player, packet) -> new PacketServerTeleport().onMoveVehicle(event, player, packet));
            routes.send(
                    ClientboundPackets.SET_PASSENGERS,
                    PacketListenerPriority.LOW,
                    (event, player, packet) -> player.packetEntityReplication.onSetPassengers(event, player, packet));
        });
    }
}
