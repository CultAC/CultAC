package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * ClientPacketListener#handleMovePlayer leaves a passenger in its seat and reports that position: in the acceptance
 * from 26.3, before then in a PosRot after the acceptance, or before it on 1.21.2 and 1.21.3. Paper takes the reported
 * position as a move once the player is off the vehicle, so the server is told the seat, never the client's report.
 */
class MountedTeleportAnswerTest {
    private static final int BOAT = 3382;
    private static final float BOAT_YAW = -171.08188F;
    private static final Vec3 START = new Vec3(1.0, 64.0, 1.5);
    private static final Vec3 BOAT_AT = new Vec3(0.19407771795977, 64.0, 0.6286785847839);
    private static final Vec3 SEAT = new Vec3(0.19409771795978, 63.5875, 0.6286785847839);
    private static final int PLACEMENT = 59;
    private static final int MOUNT = 60;
    private static final int TELEPORT = 61;
    /** Where the teleport sends the player, and where a client lying about its answer claims to be. */
    private static final Vec3 CLAIMED = new Vec3(4.5, 74.0, 0.5);

    /** How the client's ClientPacketListener#handleMovePlayer answers a player_position. */
    private enum Answer {
        /** 26.3: the acceptance carries the position. */
        CARRIES_POSITION,
        /** 1.21.4 through 26.2: the acceptance, then the PosRot. */
        ACK_THEN_POS_ROT,
        /** 1.21.2 and 1.21.3: the PosRot, then the acceptance. */
        POS_ROT_THEN_ACK
    }

    @Test
    void a263AcceptanceIsForwardedFromTheSeat() throws Exception {
        try (var f = new JavaTickFixture()) {
            assertForwardedFromTheSeat(f, Answer.CARRIES_POSITION);
        }
    }

    @Test
    void aPosRotAfterTheAcceptanceIsForwardedFromTheSeat() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_2, ClientVersion.V_26_2)) {
            assertForwardedFromTheSeat(f, Answer.ACK_THEN_POS_ROT);
        }
    }

    @Test
    void aPosRotBeforeTheAcceptanceIsForwardedFromTheSeat() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_3, ClientVersion.V_1_21_2)) {
            assertForwardedFromTheSeat(f, Answer.POS_ROT_THEN_ACK);
        }
    }

    /** An on-foot 26.3 acceptance at its exact target is forwarded as the client sent it. */
    @Test
    void anOnFootAcceptanceKeepsTheClientsPacket() throws Exception {
        try (var f = new JavaTickFixture()) {
            place(f, Answer.CARRIES_POSITION);
            f.pongThrough(sendTeleport(f, TELEPORT, CLAIMED));
            var ack = new ServerboundAcceptTeleportation(
                    TELEPORT, JavaTickFixture.vec3d(CLAIMED), f.player.xRot, f.player.yRot);
            var event = accept(f, ack);
            assertSame(ack, event.getPacket());
        }
    }

    private static void assertForwardedFromTheSeat(JavaTickFixture f, Answer answer) {
        ride(f, answer);
        f.pongThrough(sendTeleport(f, TELEPORT, CLAIMED));
        var event = answer(f, answer, TELEPORT, CLAIMED);

        assertFalse(event.isCancelled());
        Vec3 seat = f.player.compensatedEntities.lastRideTickSeat();
        Vec3d forwarded = forwardedPosition(event.getPacket());
        assertEquals(new Vec3d(seat.x, seat.y, seat.z), forwarded);
        // The boat's seat, not the boat's own position or the claim.
        assertTrue(new Vec3(forwarded.x(), forwarded.y(), forwarded.z()).distanceTo(SEAT) < 1.0E-4, "at " + forwarded);
        if (event.getPacket() instanceof ServerboundAcceptTeleportation ack) {
            assertEquals(TELEPORT, ack.id());
            assertEquals(f.player.xRot, ack.yaw());
            assertEquals(f.player.yRot, ack.pitch());
        }
    }

    /** The boat mount from {@link PassengerRotationPairTest}, each teleport answered as {@code answer}, and its ride tick. */
    private static void ride(JavaTickFixture f, Answer answer) {
        place(f, answer);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_AT, 0, BOAT_YAW, 0);
        f.pongThrough(f.player.lastTransactionSent.get());

        int mount = f.startRiding(BOAT, MOUNT, SEAT, BOAT_AT, BOAT_YAW, 0.0F);
        int vehicleMove = f.player.lastTransactionSent.get();
        f.pongThrough(mount - 1);
        assertFalse(answer(f, answer, MOUNT, SEAT).isCancelled());
        f.pongThrough(mount);
        f.moveVehicle(BOAT_AT, BOAT_YAW, 0, true);
        f.pongThrough(vehicleMove);
        f.tickEnd();
        assertTrue(f.player.inVehicle());
        assertNotNull(f.player.compensatedEntities.lastRideTickSeat());
    }

    /** The player on the floor at {@link #START}, placed there by a teleport answered as {@code answer}. */
    private static void place(JavaTickFixture f, Answer answer) {
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        f.place(START, Vec3.ZERO);
        f.pongThrough(sendTeleport(f, PLACEMENT, START));
        assertFalse(answer(f, answer, PLACEMENT, START).isCancelled());
        f.tick(START, true);
    }

    /** Sends the player_position {@code id} to {@code target}; returns its leading ping. */
    private static int sendTeleport(JavaTickFixture f, int id, Vec3 target) {
        var teleport = new ClientboundPlayerPosition(
                id, JavaTickFixture.vec3d(target), new Vec3d(0, 0, 0), 0, 0, Set.of(Relative.Y_ROT, Relative.X_ROT));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_POSITION, teleport, false);
        new PacketServerTeleport().onPlayerPosition(event, f.player, teleport);
        int leading = f.player.lastTransactionSent.get();
        event.getTasksAfterSend().forEach(Runnable::run);
        return leading;
    }

    /**
     * The client's answer to the teleport {@code id} claiming {@code at}, with the rotation the client keeps through a
     * teleport that leaves it; the event of the packet carrying the position.
     */
    private static PacketReceiveEvent<? extends ServerboundPacket> answer(
            JavaTickFixture f, Answer answer, int id, Vec3 at) {
        var idOnly = new ServerboundAcceptTeleportation(id, null, 0, 0);
        return switch (answer) {
            case CARRIES_POSITION ->
                accept(
                        f,
                        new ServerboundAcceptTeleportation(
                                id, JavaTickFixture.vec3d(at), f.player.xRot, f.player.yRot));
            case ACK_THEN_POS_ROT -> {
                assertFalse(accept(f, idOnly).isCancelled());
                yield posRot(f, at);
            }
            case POS_ROT_THEN_ACK -> {
                var posRot = posRot(f, at);
                assertFalse(accept(f, idOnly).isCancelled());
                yield posRot;
            }
        };
    }

    private static PacketReceiveEvent<ServerboundAcceptTeleportation> accept(
            JavaTickFixture f, ServerboundAcceptTeleportation ack) {
        var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, ack);
        new PacketServerTeleport().onAcceptTeleportation(event, f.player, ack);
        return event;
    }

    /** ClientPacketListener#handleMovePlayer's PosRot claiming {@code at}, with the rotation it keeps. */
    private static PacketReceiveEvent<ServerboundMovePlayer> posRot(JavaTickFixture f, Vec3 at) {
        var packet =
                new ServerboundMovePlayer(at.x, at.y, at.z, f.player.xRot, f.player.yRot, false, false, true, true);
        var event = f.event(ServerboundPackets.MOVE_PLAYER, packet);
        f.listener.onMovePlayer(event, f.player, packet);
        return event;
    }

    private static Vec3d forwardedPosition(ServerboundPacket packet) {
        if (packet instanceof ServerboundAcceptTeleportation ack) return ack.position();
        var move = (ServerboundMovePlayer) packet;
        assertTrue(move.hasPosition());
        return new Vec3d(move.x(), move.y(), move.z());
    }
}
