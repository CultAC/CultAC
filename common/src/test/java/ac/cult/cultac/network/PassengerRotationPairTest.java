package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundKeepAlive;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * LocalPlayer#sendChanges sends a mounted Rot and the root's MoveVehicle back to back on the client's main thread.
 * ClientCommonPacketListenerImpl#handleKeepAlive answers from the network thread, so its KeepAlive can come between
 * them; a packet the main thread sent cannot.
 */
class PassengerRotationPairTest {
    private static final int BOAT = 3382;
    private static final float BOAT_YAW = -171.08188F;
    private static final Vec3 START = new Vec3(1.0, 64.0, 1.5);
    private static final Vec3 BOAT_AT = new Vec3(0.19407771795977, 64.0, 0.6286785847839);
    private static final Vec3 SEAT = new Vec3(0.19409771795978, 63.5875, 0.6286785847839);

    @Test
    void aKeepAliveBetweenTheRotAndItsMoveVehicleKeepsThemOneTick() throws Exception {
        try (var f = new JavaTickFixture()) {
            ride(f);
            rot(f);
            var keepAlive = new ServerboundKeepAlive(7L);
            CheckManagerListener.receiveGeneric(f.event(ServerboundPackets.KEEP_ALIVE, keepAlive), f.player);
            assertTrue(f.player.packetStateData.isAwaitingVehicleMoveAfterPassengerRotation());
        }
    }

    @Test
    void aMainThreadPacketBetweenTheRotAndAMoveVehicleSeparatesThem() throws Exception {
        try (var f = new JavaTickFixture()) {
            ride(f);
            rot(f);
            CheckManagerListener.receiveGeneric(f.event(ServerboundPackets.SWING, ServerboundSwing.PUNCH), f.player);
            assertFalse(f.player.packetStateData.isAwaitingVehicleMoveAfterPassengerRotation());
        }
    }

    private static void ride(JavaTickFixture f) {
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        f.place(START, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_AT, 0, BOAT_YAW, 0);
        f.pongThrough(f.player.lastTransactionSent.get());
        f.playerPosition(START, Vec3.ZERO, Set.of());
        f.tick(START, true);

        int mount = f.startRiding(BOAT, 60, SEAT, BOAT_AT, BOAT_YAW, 0.0F);
        int vehicleMove = f.player.lastTransactionSent.get();
        f.pongThrough(mount - 1);
        var accept = new ServerboundAcceptTeleportation(60, new Vec3d(SEAT.x, SEAT.y, SEAT.z), BOAT_YAW, 0.0F);
        new PacketServerTeleport()
                .onAcceptTeleportation(f.event(ServerboundPackets.ACCEPT_TELEPORTATION, accept), f.player, accept);
        f.pongThrough(mount);
        f.moveVehicle(BOAT_AT, BOAT_YAW, 0, true);
        f.pongThrough(vehicleMove);
        f.tickEnd();
        assertTrue(f.player.inVehicle());
    }

    private static void rot(JavaTickFixture f) {
        var rot = new ServerboundMovePlayer(0, 0, 0, BOAT_YAW, 0.0F, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        assertTrue(f.player.packetStateData.isAwaitingVehicleMoveAfterPassengerRotation());
    }
}
