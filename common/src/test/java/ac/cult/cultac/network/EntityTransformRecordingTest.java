package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Relative teleports observe packet-handler transforms. Full updates replace all components; partial updates keep
 * the other components from this tick's transform, or the current client interpolation/position after a tick.
 */
class EntityTransformRecordingTest {
    private static final int ENTITY = 50;
    private static final Set<Relative> TRANSFORM_RELATIVES =
            Set.of(Relative.X, Relative.Y, Relative.Z, Relative.Y_ROT, Relative.X_ROT);

    @Test
    void fullPositionAndRotationSurvivePartialUpdatesAndChainedTeleports() throws Exception {
        try (var f = fixture()) {
            move(f.player, 4096, true, true, 30, 40);
            move(f.player, 4096, true, false, 0, 0);
            move(f.player, 0, false, true, 50, 60);
            teleport(f.player, new Vec3d(0.25, 0.5, -0.75), 5, -7);
            assertTransform(f.player, 3.25, 64.5, 1.25, 55, 53);
            teleport(f.player, new Vec3d(0.75, 0, 0.75), 10, 10);
            assertTransform(f.player, 4, 64.5, 2, 65, 63);
        }
    }

    @Test
    void partialUpdateAfterClientTickUsesClientPositionInsteadOfStaleSentTransform() throws Exception {
        try (var f = fixture()) {
            move(f.player, 4096, true, true, 30, 40);
            // No proof has applied the move. This real client tick invalidates the recorded packet transform.
            f.player.packetEntityReplication.onPlayerTickEnd(null);
            assertEquals(1.0, f.player.compensatedEntities.getEntity(ENTITY).clientPhysicalPosition.x);
            move(f.player, 0, false, true, 50, 60);
            teleport(f.player, new Vec3d(0.25, 0.5, -0.75), 5, -7);
            assertTransform(f.player, 1.25, 64.5, 1.25, 55, 53);
        }
    }

    @Test
    void teleportReadsPositionAndBothAnglesFromOneActiveInterpolationTarget() throws Exception {
        try (var f = fixture()) {
            var entity = f.player.compensatedEntities.getEntity(ENTITY);
            entity.onFirstTransaction(false, true, 20, 70, 30, true, 80, 40, f.player);
            assertTrue(entity.newPacketLocation.hasActiveInterpolationTarget());
            assertEquals(
                    1.0, entity.clientPhysicalPosition.x, "the source must be the target, not the physical position");
            teleport(f.player, new Vec3d(1, 2, 3), 5, 6);
            assertTransform(f.player, 21, 72, 33, 85, 46);
        }
    }

    @Test
    void aPendingEntityFallsBackToItsTrackerForPartialUpdatesAndTeleports() throws Exception {
        try (var f = fixture()) {
            // The server tracker can exist before the spawn's receipt makes the compensated entity available.
            f.player.compensatedEntities.removeEntity(ENTITY);
            move(f.player, 4096, true, true, 30, 40);
            move(f.player, 0, false, true, 50, 60);
            teleport(f.player, new Vec3d(0.25, 0.5, -0.75), 5, -7);
            assertTransform(f.player, 2.25, 64.5, 1.25, 55, 53);
        }
    }

    private static JavaTickFixture fixture() throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11);
        f.place(new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5), Vec3.ZERO);
        f.player.packetEntityReplication.addEntity(ENTITY, EntityTypeIds.ZOMBIE, new Vec3(1, 64, 2), 10, 20, 0, -1);
        int spawn = f.player.lastTransactionSent.get();
        f.player.lastTransactionReceived.set(spawn);
        f.player.latencyUtils.handleNettySyncTransaction(spawn);
        f.player.lastTransactionSent.incrementAndGet();
        return f;
    }

    private static void move(
            CultPlayer player, int dx, boolean hasPosition, boolean hasRotation, float yaw, float pitch) {
        var packet = new ClientboundMoveEntity(
                ENTITY,
                new EntityDelta.Linear((short) dx, (short) 0, (short) 0),
                yaw,
                pitch,
                true,
                hasPosition,
                hasRotation);
        var event =
                new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, true);
        event.sharedPing(player.lastTransactionSent.get());
        player.packetEntityReplication.onMoveEntity(event, player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    private static void teleport(CultPlayer player, Vec3d delta, float yaw, float pitch) {
        var packet = new ClientboundTeleportEntity(ENTITY, delta, Vec3d.ZERO, yaw, pitch, TRANSFORM_RELATIVES, true);
        var event = new PacketSendEvent<>(
                player.user, ConnectionPhase.PLAY, ClientboundPackets.TELEPORT_ENTITY, packet, true);
        event.sharedPing(player.lastTransactionSent.get());
        player.packetEntityReplication.onTeleportEntity(event, player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    private static void assertTransform(CultPlayer player, double x, double y, double z, float yaw, float pitch) {
        var data = player.compensatedEntities.getTrackedEntity(ENTITY);
        assertEquals(x, data.getX());
        assertEquals(y, data.getY());
        assertEquals(z, data.getZ());
        assertEquals(yaw, data.getXRot());
        assertEquals(pitch, data.getYRot());
    }
}
