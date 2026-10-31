package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
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
            assertEquals(2, setbacks.queuedVehicleTeleportCount());
            assertTrue(setbacks.checkVehicleTeleportQueue(VEHICLE, nudge.x, nudge.y, nudge.z)
                    .isTeleport());
            assertTrue(setbacks.checkVehicleTeleportQueue(VEHICLE, tracked.x, tracked.y, tracked.z)
                    .isTeleport());
            assertEquals(0, setbacks.queuedVehicleTeleportCount());
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

    @Test
    void aResyncThatAlreadySnapsIsSentAlone() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 tracked = new Vec3(0.75, FLOOR_Y, 0.5);
            addVehicle(f, EntityTypeIds.OAK_BOAT, new Vec3(0.5, FLOOR_Y, 0.5), tracked);

            List<ClientboundMoveVehicle> moves = mountMoves(f);

            assertEquals(1, moves.size());
            assertEquals(tracked, position(moves.get(0)));
            assertEquals(1, f.player.getSetbackTeleportUtil().queuedVehicleTeleportCount());
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
            assertEquals(1, f.player.getSetbackTeleportUtil().queuedVehicleTeleportCount());
        }
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
