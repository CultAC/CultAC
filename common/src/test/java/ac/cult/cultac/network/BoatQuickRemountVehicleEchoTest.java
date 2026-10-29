package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsVehicle;
import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.VehicleOffset;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPaddleBoat;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A server can mount and dismount a 26.3 client before the client processes either. The client accepts the mount's
 * player_position before ClientPacketListener#handleSetEntityPassengersPacket mounts it, then
 * ClientPacketListener#handleMoveVehicle answers the mount's move_vehicle from the boat it now rides. The boat and seat
 * come from the capture, moved next to the fixture's floor.
 */
class BoatQuickRemountVehicleEchoTest {
    private static final int BOAT = 3382;
    private static final float BOAT_YAW = -171.08188F;
    private static final Vec3 START = new Vec3(1.0, 64.0, 1.5);
    private static final Vec3 BOAT_AT = new Vec3(0.19407771795977, 64.0, 0.6286785847839);
    private static final Vec3 SEAT = new Vec3(0.19409771795978, 63.5875, 0.6286785847839);

    @Test
    void theMountsVehicleEchoAndRideTickBeforeAProcessedDismountAreAccepted() throws Exception {
        try (var f = new JavaTickFixture()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.player.setExperimentalChecks(true);
            f.place(START, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_AT, 0, BOAT_YAW, 0);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.playerPosition(START, Vec3.ZERO, Set.of());
            f.tick(START, true);

            int mount = f.startRiding(BOAT, 60, SEAT, BOAT_AT, BOAT_YAW, 0.0F);
            int vehicleMove = f.player.lastTransactionSent.get();
            f.setPassengers(BOAT, List.of());
            int dismount = f.player.lastTransactionSent.get();
            f.tick(START, true);

            f.pongThrough(mount - 1);
            var accept = new ServerboundAcceptTeleportation(60, new Vec3d(SEAT.x, SEAT.y, SEAT.z), BOAT_YAW, 24.0F);
            new PacketServerTeleport()
                    .onAcceptTeleportation(f.event(ServerboundPackets.ACCEPT_TELEPORTATION, accept), f.player, accept);
            f.pongThrough(mount);
            f.moveVehicle(BOAT_AT, BOAT_YAW, 0, true);
            f.pongThrough(vehicleMove);
            assertTrue(f.player.inVehicle());

            var paddle = new ServerboundPaddleBoat(false, false);
            f.listener.onPaddleBoat(f.event(ServerboundPackets.PADDLE_BOAT, paddle), f.player, paddle);
            var rot = new ServerboundMovePlayer(0, 0, 0, BOAT_YAW, 24.087463F, false, false, false, true);
            f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
            f.moveVehicle(BOAT_AT, BOAT_YAW, 0, true);
            f.tickEnd();

            f.pongThrough(dismount);
            assertFalse(f.player.inVehicle());
            assertEquals(0, f.player.checkManager.getCheck(BadPacketsVehicle.class).violations);
            assertEquals(0, f.player.checkManager.getCheck(VehicleOffset.class).violations);
            assertEquals(0, f.player.checkManager.getCheck(OffsetHandler.class).violations);
        }
    }
}
