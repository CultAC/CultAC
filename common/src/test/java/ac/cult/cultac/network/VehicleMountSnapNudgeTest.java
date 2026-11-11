package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ClientPacketListener#handleMoveVehicle snaps only for a packet more than 1.0E-5F from the vehicle. A mount resync at
 * the vehicle's own position is preceded by a nudge in the same bundle, so the client snaps twice and ends exactly on
 * the tracked position, never ticking at the nudge (issue #193: a boat flush against a wall drove through it).
 */
class VehicleMountSnapNudgeTest {
    private static final int VEHICLE = 100;
    private static final double SNAP_DISTANCE = 1.0E-5F;

    @Test
    void aResyncAtTheVehiclesPositionNudgesThenSnapsBackToIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 tracked = new Vec3(0.6875, FLOOR_Y, 0.6875);
            addVehicle(f, EntityTypeIds.OAK_BOAT, tracked, tracked);

            List<ClientboundMoveVehicle> moves = mountMoves(f);

            assertEquals(2, moves.size());
            Vec3 nudge = position(moves.get(0));
            assertEquals(tracked, position(moves.get(1)));
            assertTrue(nudge.distanceTo(tracked) > SNAP_DISTANCE, "the nudge must snap");
            assertTrue(Math.abs(nudge.x - tracked.x) < SNAP_DISTANCE);
            assertTrue(Math.abs(nudge.y - tracked.y) < SNAP_DISTANCE);
            assertTrue(Math.abs(nudge.z - tracked.z) < SNAP_DISTANCE);

            // Each packet is answered from the packet handler, the nudge first.
            var setbacks = f.player.getSetbackTeleportUtil();
            assertEquals(2, setbacks.vehicleTeleports().queuedVehicleTeleportCount());
            assertTrue(setbacks.vehicleTeleports().checkVehicleTeleportQueue(VEHICLE, nudge.x, nudge.y, nudge.z)
                    .isTeleport());
            assertTrue(setbacks.vehicleTeleports().checkVehicleTeleportQueue(VEHICLE, tracked.x, tracked.y, tracked.z)
                    .isTeleport());
            assertEquals(0, setbacks.vehicleTeleports().queuedVehicleTeleportCount());
        }
    }

    @Test
    void theNudgePointsAwayFromAClientPositionJustOffTheTrackedOne() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 tracked = new Vec3(0.5, FLOOR_Y, 0.5);
            Vec3 client = new Vec3(0.5 + 4.0E-6, FLOOR_Y, 0.5 - 4.0E-6);
            addVehicle(f, EntityTypeIds.OAK_BOAT, client, tracked);

            List<ClientboundMoveVehicle> moves = mountMoves(f);

            assertEquals(2, moves.size());
            assertTrue(position(moves.get(0)).distanceTo(client) > SNAP_DISTANCE, "the nudge must snap");
            assertEquals(tracked, position(moves.get(1)));
        }
    }

    /** A boat the client already drives as sent stays where the client moved it, which the resync alone snaps. */
    @Test
    void aResyncThatAlreadySnapsADrivenVehicleIsSentAlone() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 tracked = new Vec3(0.75, FLOOR_Y, 0.5);
            addVehicle(f, EntityTypeIds.OAK_BOAT, new Vec3(0.5, FLOOR_Y, 0.5), tracked);
            mountMoves(f);
            int queued = f.player.getSetbackTeleportUtil().vehicleTeleports().queuedVehicleTeleportCount();

            List<ClientboundMoveVehicle> moves = mountMoves(f);

            assertEquals(1, moves.size());
            assertEquals(tracked, position(moves.get(0)));
            assertEquals(
                    queued + 1, f.player.getSetbackTeleportUtil().vehicleTeleports().queuedVehicleTeleportCount());
        }
    }

    /**
     * A boat the client does not drive yet holds the last position sent to it as its interpolation target, here a
     * move whose proof has not returned when the set_passengers is sent: the resync at that position takes the nudge.
     */
    @Test
    void aMountBehindAnUnprovenMoveStillNudges() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 spawn = new Vec3(0.5, FLOOR_Y + 2, 1.5);
            send(f, ClientboundPackets.ADD_ENTITY, new ClientboundAddEntity(
                    VEHICLE, "minecraft:oak_boat", new Vec3d(spawn.x, spawn.y, spawn.z), 0, 0, 0));
            f.pongThrough(f.player.lastTransactionSent.get());
            send(f, ClientboundPackets.MOVE_ENTITY, new ClientboundMoveEntity(
                    VEHICLE, new EntityDelta.Linear((short) 0, (short) -1024, (short) 0), 0, 0, false, true, false));
            Vec3 tracked = spawn.add(0.0, -0.25, 0.0);

            List<ClientboundMoveVehicle> moves = mountMoves(f);

            assertEquals(2, moves.size());
            assertTrue(position(moves.get(0)).distanceTo(tracked) > SNAP_DISTANCE, "the nudge must snap");
            assertEquals(tracked, position(moves.get(1)));
        }
    }

    /** Geyser's JavaMoveVehicleTranslator applies every MoveVehicle, so a Bedrock rider never gets the nudge. */
    @Test
    void aBedrockRiderGetsOnlyTheResyncPosition() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.movementPlatform = MovementPlatform.BEDROCK;
            Vec3 tracked = new Vec3(0.5, FLOOR_Y, 0.5);
            // Camels are not BedrockVehicleControl vehicles, so they keep the Java MoveVehicle resync.
            addVehicle(f, EntityTypeIds.CAMEL, tracked, tracked);
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;

            List<ClientboundMoveVehicle> moves = mountMoves(f);

            assertEquals(1, moves.size());
            assertEquals(tracked, position(moves.get(0)));
            assertEquals(1, f.player.getSetbackTeleportUtil().vehicleTeleports().queuedVehicleTeleportCount());
        }
    }

    private static <T extends ClientboundPacket> void send(JavaTickFixture f, PacketType<T> type, T packet) {
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet, false);
        if (packet instanceof ClientboundAddEntity add) {
            f.player.packetEntityReplication.onAddEntity(cast(event), f.player, add);
        } else if (packet instanceof ClientboundMoveEntity move) {
            f.player.packetEntityReplication.onMoveEntity(cast(event), f.player, move);
        }
        event.tasksAfter().forEach(Runnable::run);
    }

    @SuppressWarnings("unchecked")
    private static <T> PacketSendEvent<T> cast(PacketSendEvent<?> event) {
        return (PacketSendEvent<T>) event;
    }

    private static void addVehicle(JavaTickFixture f, int type, Vec3 client, Vec3 tracked) {
        f.player.compensatedEntities.addEntity(VEHICLE, type, client, 0, 0, 0);
        f.player.compensatedEntities.serverPositionsMap.put(
                VEHICLE, new TrackerData(tracked.x, tracked.y, tracked.z, 0, 0, type, 0));
    }

    /** The MoveVehicle packets a set_passengers mounting the local player writes after itself, in order. */
    private static List<ClientboundMoveVehicle> mountMoves(JavaTickFixture f) {
        var packet = new ClientboundSetPassengers(VEHICLE, List.of(f.player.entityID));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        f.player.packetEntityReplication.onSetPassengers(event, f.player, packet);
        return event.writesAfter().stream()
                .map(CultWrite::packet)
                .filter(ClientboundMoveVehicle.class::isInstance)
                .map(ClientboundMoveVehicle.class::cast)
                .toList();
    }

    private static Vec3 position(ClientboundMoveVehicle move) {
        return new Vec3(
                move.position().x(), move.position().y(), move.position().z());
    }
}
