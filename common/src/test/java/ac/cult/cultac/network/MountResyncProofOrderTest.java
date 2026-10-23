package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A mount resync bundles set_passengers with a generated move_vehicle and set_entity_motion.
 * ClientPacketListener#handleBundlePacket handles the bundle in one task, and a pong answers each
 * ping as it is handled, so every proof the bundle carries must follow set_passengers inside it and
 * rise in wire order: an earlier pong cannot prove a later packet.
 */
class MountResyncProofOrderTest {
    private static final int BOAT = 100;
    private static final String SET_PASSENGERS = "set_passengers";

    @Test
    void mountResyncProofsFollowTheMountInsideItsBundleInWireOrder() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.recordProvenance(true);
            Vec3 seat = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(seat, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, seat, 0, 0, 0);
            f.player.compensatedEntities.serverPositionsMap.put(
                    BOAT, new TrackerData(seat.x, seat.y, seat.z, 0, 0, EntityTypeIds.OAK_BOAT, 0));
            int before = f.player.lastTransactionSent.get();

            var packet = new ClientboundSetPassengers(BOAT, List.of(f.player.entityID));
            var event = event(f, ClientboundPackets.SET_PASSENGERS, packet);
            f.player.packetEntityReplication.onSetPassengers(event, f.player, packet);
            assertTrue(event.isBundleRequested(), "the resync is one bundle");

            // CultEncoder#expandFrame: the packet, then its writes, each through its own send handlers.
            var wire = new ArrayList<Object>();
            var tasks = new ArrayList<Runnable>();
            wire.add(SET_PASSENGERS);
            for (CultWrite write : event.writesAfter()) expand(f, write, wire, tasks);
            tasks.addAll(event.tasksAfter());
            tasks.forEach(Runnable::run);

            assertTrue(wire.stream().anyMatch(ClientboundMoveVehicle.class::isInstance), wire.toString());
            assertTrue(wire.stream().anyMatch(ClientboundEntityMotion.class::isInstance), wire.toString());
            Map<Integer, Integer> transactions = new HashMap<>();
            for (int transaction = before + 1; transaction <= f.player.lastTransactionSent.get(); transaction++) {
                Integer wireId = f.player.wireIdOf(transaction);
                assertNotNull(wireId);
                transactions.put(wireId, transaction);
            }
            List<Integer> pings = wire.stream()
                    .filter(ClientboundPing.class::isInstance)
                    .map(ping -> transactions.get(((ClientboundPing) ping).id()))
                    .toList();
            // A ping written outside the bundle (a synchronous leading ping) never reaches it.
            assertEquals(transactions.size(), pings.size(), "every proof is inside the bundle: " + wire);
            for (int i = 1; i < pings.size(); i++) {
                assertTrue(pings.get(i) > pings.get(i - 1), "proofs rise in wire order: " + pings);
            }
            assertEquals(SET_PASSENGERS, wire.getFirst());

            // The client answers in wire order; no answer precedes its handling of set_passengers.
            assertFalse(f.player.compensatedEntities.getSelf().inVehicle());
            int received = f.player.lastTransactionReceived.get();
            for (Object sent : wire) {
                if (!(sent instanceof ClientboundPing ping)) continue;
                assertTrue(f.player.addTransactionResponse(ping.id()));
                assertTrue(f.player.lastTransactionReceived.get() > received, "acknowledgement went back");
                received = f.player.lastTransactionReceived.get();
                assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
            }
        }
    }

    private static void expand(JavaTickFixture f, CultWrite write, List<Object> wire, List<Runnable> tasks) {
        Object packet = write.packet();
        PacketSendEvent<?> event;
        if (packet instanceof ClientboundPing ping) {
            // PacketPingListener#onPing.
            f.player.markTransactionPacketSent(ping.id(), System.currentTimeMillis());
            wire.add(ping);
            return;
        } else if (packet instanceof ClientboundMoveVehicle move) {
            var moveEvent = event(f, ClientboundPackets.MOVE_VEHICLE, move);
            moveEvent.authored();
            new PacketServerTeleport().onMoveVehicle(moveEvent, f.player, move);
            event = moveEvent;
        } else if (packet instanceof ClientboundEntityMotion motion) {
            var motionEvent = event(f, ClientboundPackets.ENTITY_MOTION, motion);
            motionEvent.authored();
            f.player.packetEntityReplication.onSetEntityMotion(motionEvent, f.player, motion);
            event = motionEvent;
        } else {
            throw new AssertionError("unexpected resync write " + packet);
        }
        for (CultWrite before : event.writesBefore()) expand(f, before, wire, tasks);
        wire.add(packet);
        for (CultWrite after : event.writesAfter()) expand(f, after, wire, tasks);
        tasks.addAll(event.tasksAfter());
    }

    private static <R extends ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket> PacketSendEvent<R> event(
            JavaTickFixture f, ac.cult.cultac.protocol.PacketType<R> type, R packet) {
        return new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet, false);
    }
}
