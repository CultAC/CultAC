package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ClientPacketListener#handleTeleportEntity interpolates a ticking vehicle the local player controls to a target under
 * 64 blocks away, and ServerboundMoveVehiclePacket reports that target while the vehicle itself is a third, then two
 * thirds of the way there. A report over 30 blocks ahead of the vehicle is still the vehicle's own, unmoved.
 */
class JavaRiddenLongTeleportTest {
    private static final int BOAT = 100;
    private static final Vec3 BOAT_AT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 TARGET = BOAT_AT.add(0, 0, 48);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @Test
    void aBoatInterpolatingAcrossFortyEightBlocksReportsItsTarget() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int chunkZ = 2; chunkZ <= 4; chunkZ++) {
                for (int chunkX = -1; chunkX <= 1; chunkX++)
                    f.player.compensatedWorld.ensureValidationChunkLoaded(chunkX, chunkZ);
            }
            for (int x = -2; x <= 2; x++) {
                for (int z = 9; z <= 52; z++)
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x, FLOOR_Y - 1, z), BlockIds.STONE.defaultState());
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();

            f.place(BOAT_AT, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_AT, 0, 0, 0);
            var boat = f.player.compensatedEntities.getEntity(BOAT);
            boat.onGround = true;
            f.setPassengers(BOAT, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(NONE);
            for (int tick = 0; tick < 6; tick++) assertVehicleExact(f.rideTick(BOAT_AT, true), tick);

            var packet = new ClientboundTeleportEntity(
                    BOAT, new Vec3d(TARGET.x, TARGET.y, TARGET.z), Vec3d.ZERO, 0, 0, Set.of(), true);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.TELEPORT_ENTITY, packet, false);
            f.player.packetEntityReplication.onTeleportEntity(event, f.player, packet);
            event.getTasksAfterSend().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());

            // AbstractBoat's three interpolation steps, then a tick at rest on the target.
            for (int step = 1; step <= 4; step++) {
                PredictionResult result = f.rideTick(TARGET, true);
                assertTrue(exact(result) && !result.isExempt(), "step " + step + ": " + describe(result));
                assertEquals(step <= 3 ? 16.0 * (3 - step) : 0.0, result.getTarget().z, 1e-9, "step " + step);
                assertEquals(
                        BOAT_AT.z + 48.0 * Math.min(step, 3) / 3.0,
                        boat.clientPhysicalPosition.z,
                        1e-9,
                        "step " + step);
            }
        }
    }
}
