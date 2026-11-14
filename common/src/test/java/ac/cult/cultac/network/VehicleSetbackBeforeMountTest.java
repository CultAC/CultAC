package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Once the server seats the player on a vehicle, a setback moves the vehicle. A setback raised before any placement of
 * that vehicle reached the safe position, such as an on-foot move before the client rides it, returns the vehicle to
 * where the server has it, never to the player's on-foot position.
 */
class VehicleSetbackBeforeMountTest {
    private static final int BOAT = 100;
    private static final Vec3 FOOT = new Vec3(0.5, FLOOR_Y, 1.7);
    private static final Vec3 BOAT_AT = FOOT.add(0, 0, -1);

    private static JavaTickFixture fixture(List<ClientboundMoveVehicle> moves) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.fixture.registrar.register(routes -> routes.send(
                ClientboundPackets.MOVE_VEHICLE, PacketListenerPriority.LOW, (event, player, packet) -> {
                    moves.add(packet);
                    new PacketServerTeleport().onMoveVehicle(event, player, packet);
                }));
        for (int x = -3; x <= 3; x++) {
            for (int y = FLOOR_Y; y <= FLOOR_Y + 2; y++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, y, 2), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(FOOT, Vec3.ZERO);
        f.player.packetEntityReplication.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_AT, 0, 0, 0, -1);
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = 0;
        f.player.yRot = 0;
        JavaTickFixture.assertVehicleExact(f.tick(FOOT, true), 0);
        return f;
    }

    @Test
    void anOnFootSetbackBeforeTheClientRidesReturnsTheVehicleToItsServerPosition() throws Exception {
        List<ClientboundMoveVehicle> moves = new ArrayList<>();
        try (JavaTickFixture f = fixture(moves)) {
            f.setPassengers(BOAT, List.of(f.player.entityID));
            assertFalse(f.player.inVehicle(), "mount applied before its pong");
            PredictionResult legit = f.tick(FOOT, true);
            assertTrue(legit.getFlags().isEmpty(), JavaTickFixture.describe(legit));
            PredictionResult flagged = f.tick(FOOT.add(0.5, 0, 0), true);
            assertTrue(f.player.getSetbackTeleportUtil().isPendingSetback(), JavaTickFixture.describe(flagged));
            assertFalse(moves.isEmpty(), "no move_vehicle sent");
            ClientboundMoveVehicle move = moves.getLast();
            Vec3 at = new Vec3(move.position().x(), move.position().y(), move.position().z());
            assertEquals(BOAT_AT, at);

            f.pongThrough(f.player.lastTransactionSent.get());
            assertTrue(f.player.inVehicle(), "mount not applied");
            f.moveVehicle(at, move.yaw(), move.pitch(), true);
            SetBackData setback = f.player.getSetbackTeleportUtil().getRequiredSetBack();
            assertTrue(setback == null || setback.isComplete(), "the answer was not accepted");
            assertEquals(BOAT_AT, f.player.compensatedEntities.getEntity(BOAT).clientPhysicalPosition);
            f.tickEnd();
            for (int i = 0; i < 3; i++) JavaTickFixture.assertVehicleExact(f.rideTick(BOAT_AT, true), i);
        }
    }

    @Test
    void anOnFootSetbackWithoutAMountMovesThePlayer() throws Exception {
        List<ClientboundMoveVehicle> moves = new ArrayList<>();
        try (JavaTickFixture f = fixture(moves)) {
            f.tick(FOOT.add(0.5, 0, 0), true);
            assertTrue(f.player.getSetbackTeleportUtil().isPendingSetback());
            assertTrue(moves.isEmpty(), "unexpected move_vehicle " + moves);
        }
    }
}
