package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.observeOnly;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * ClientPacketListener#handleEntityPositionSync skips a boat while Entity#isLocalInstanceAuthoritative, which the
 * passengers the client has handled decide. A sync the server sent while the player drove the boat stays skipped when
 * the server has since moved the player to another boat the client has not mounted yet, so the boat's next ride tick
 * starts where the client left it. Taken from a capture of a player switching boats at 605 ms ping.
 */
class BoatSwitchPositionSyncTest {
    private static final int BOAT = 22;
    private static final int OTHER_BOAT = 23;
    private static final float BOAT_YAW = -88.037506F;
    private static final Vec3 REST = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 SEAT = REST.add(0, -0.4125, 0);
    private static final Vec3 OTHER_REST = REST.add(2, 0, 0.5);
    private static final Vec3 OTHER_SEAT = OTHER_REST.add(0, -0.4125, 0);
    /** Far enough that one AbstractBoat#lerp step toward it moves the boat a third of a block. */
    private static final Vec3 STALE_SYNC = REST.add(-1, 0, 0);

    @Test
    void aSyncSentWhileDrivingTheBoatLeftForAnotherMovesNothing() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.place(SEAT, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, REST, 0, BOAT_YAW, 0);
            f.player.compensatedEntities.addEntity(OTHER_BOAT, EntityTypeIds.OAK_BOAT, OTHER_REST, 0, BOAT_YAW, 0);
            board(f, f.startRiding(BOAT, 1, SEAT, REST, BOAT_YAW, 0.0F));
            assertVehicleExact(rideTick(f), 0);

            int syncProof = sync(f, BOAT, STALE_SYNC);
            f.setPassengers(BOAT, List.of());
            f.startRiding(OTHER_BOAT, 2, OTHER_SEAT, OTHER_REST, BOAT_YAW, 0.0F);
            // The client handles the sync, then ticks the boat before it handles the switch.
            f.pongThrough(syncProof);
            assertVehicleExact(rideTick(f), 1);
        }
    }

    /** The client's answers to startRiding with the ping {@code trailing} its teleport: accept, PosRot and the echo. */
    private static void board(JavaTickFixture f, int trailing) {
        f.pongThrough(trailing - 1);
        f.acceptTeleport(1);
        var posRot = new ServerboundMovePlayer(SEAT.x, SEAT.y, SEAT.z, BOAT_YAW, 20.0F, false, false, true, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, posRot), f.player, posRot);
        f.pongThrough(trailing);
        f.moveVehicle(REST, BOAT_YAW, 0, true);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** The rider's Rot, the boat staying at rest and the tick end. */
    private static PredictionResult rideTick(JavaTickFixture f) {
        var rot = new ServerboundMovePlayer(0, 0, 0, BOAT_YAW, 20.0F, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        f.moveVehicle(REST, BOAT_YAW, 0, true);
        f.tickEnd();
        return f.player.checkManager.getSimulationProcessor().getLastPrediction();
    }

    /** An entity_position_sync placing {@code id} at {@code at}; returns the ping proving the client handled it. */
    private static int sync(JavaTickFixture f, int id, Vec3 at) {
        var path = new PositionPath.Linear(new Vec3d(at.x, at.y, at.z));
        var packet = new ClientboundEntityPositionSync(id, path, BOAT_YAW, 0, true);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_POSITION_SYNC, packet, false);
        f.player.packetEntityReplication.onEntityPositionSync(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.sendTrailingTransaction();
    }
}
