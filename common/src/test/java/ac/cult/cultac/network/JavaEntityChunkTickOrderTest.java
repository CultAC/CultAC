package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JavaEntityChunkTickOrderTest {
    /** Unticked entities snap on position-sync packets; entering a ticking section appends them immediately. */
    @Test
    void aPositionSyncInAnUnloadedChunkSnapsAndCanReenterThroughALoadedChunk() throws Exception {
        try (var f = new JavaTickFixture()) {
            var entities = f.player.compensatedEntities;
            f.player.compensatedWorld.removeChunkLater(0, 1, 0);
            entities.addEntity(100, EntityTypeIds.OAK_BOAT, new Vec3(0.5, 80, 20.5), 0, 0, 0);
            positionSync(f, 100, new Vec3(2.5, 80, 20.5));
            assertEquals(new Vec3(2.5, 80, 20.5), entities.getEntity(100).clientPhysicalPosition);
            assertEquals(List.of(), ticks(f));
            entities.addEntity(101, EntityTypeIds.OAK_BOAT, new Vec3(4.5, 80, 0.5), 0, 0, 0);
            positionSync(f, 100, new Vec3(2.5, 80, 0.5));
            assertEquals(new Vec3(2.5, 80, 0.5), entities.getEntity(100).clientPhysicalPosition);
            assertEquals(List.of(101, 100), ticks(f));
        }
    }

    /** Retained non-players stop interpolating in an unloaded chunk and re-enter behind newer ticking entities. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void anUnloadedEntityDoesNotTickAndReloadAppendsIt(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(
                    ac.cult.cultac.player.CultPlayer.class, f.player, "resolvedClientVersion", version);
            var world = f.player.compensatedWorld;
            var entities = f.player.compensatedEntities;
            var chunk = world.getChunk(0, 1);
            entities.addEntity(100, EntityTypeIds.OAK_BOAT, new Vec3(0.5, 80, 20.5), 0, 0, 0);
            PacketEntity first = entities.getEntity(100);
            first.onBundledPositionSyncTransaction(3.5, 80, 20.5, f.player);
            Vec3 before = first.clientPhysicalPosition;
            world.removeChunkLater(0, 1, 0);
            assertFalse(world.isChunkLoaded(0, 1));
            for (int tick = 0; tick < 3; tick++) entities.tickClientEntities(entity -> entity.onMovement(f.player));
            assertEquals(before, first.clientPhysicalPosition, "unloaded retained entities must not interpolate");
            assertFalse(ticks(f).contains(100));
            entities.addEntity(101, EntityTypeIds.OAK_BOAT, new Vec3(0.5, 80, 0.5), 0, 0, 0);
            world.putChunk(0, 1, chunk);
            assertTrue(first.getClientTickOrder() > entities.getEntity(101).getClientTickOrder());
            assertEquals(List.of(101, 100), ticks(f));
            entities.tickClientEntities(entity -> entity.onMovement(f.player));
            assertTrue(first.clientPhysicalPosition.x > before.x);
        }
    }

    /** Section callbacks remove a moving entity; a later move into a ticking section appends it. */
    @Test
    void crossingAnUnloadedChunkBoundaryChangesMembershipAndReentryOrder() throws Exception {
        try (var f = new JavaTickFixture()) {
            var entities = f.player.compensatedEntities;
            f.player.compensatedWorld.removeChunkLater(0, 1, 0);
            entities.addEntity(100, EntityTypeIds.OAK_BOAT, new Vec3(0.5, 80, 0.5), 0, 0, 0);
            PacketEntity first = entities.getEntity(100);
            first.setPositionRaw(first.getExactMovementCollisionBox().copy().offset(0, 0, 16));
            assertEquals(List.of(), ticks(f));
            entities.addEntity(101, EntityTypeIds.OAK_BOAT, new Vec3(4.5, 80, 0.5), 0, 0, 0);
            first.setPositionRaw(first.getExactMovementCollisionBox().copy().offset(0, 0, -16));
            assertEquals(List.of(101, 100), ticks(f));
        }
    }

    /** EntityTickList keeps the active iteration stable when an earlier entity stops ticking during its tick. */
    @Test
    void leavingDuringInterpolationDoesNotSkipTheLaterEntity() throws Exception {
        try (var f = new JavaTickFixture()) {
            var entities = f.player.compensatedEntities;
            f.player.compensatedWorld.removeChunkLater(0, 1, 0);
            entities.addEntity(100, EntityTypeIds.OAK_BOAT, new Vec3(0.5, 80, 15.5), 0, 0, 0);
            entities.addEntity(101, EntityTypeIds.OAK_BOAT, new Vec3(4.5, 80, 0.5), 0, 0, 0);
            List<Integer> visited = new ArrayList<>();
            entities.tickClientEntities(entity -> {
                visited.add(entity.getEntityId());
                if (entity.getEntityId() == 100)
                    entity.setPositionRaw(
                            entity.getExactMovementCollisionBox().copy().offset(0, 0, 2));
            });
            assertEquals(List.of(100, 101), visited);
            assertEquals(List.of(101), ticks(f));
        }
    }

    /** A chunk load follows SectionPos's packed Y order, then ClassInstanceMultiMap's section insertion order. */
    @Test
    void aReloadVisitsSectionsBeforeTheirEntityInsertionOrder() throws Exception {
        try (var f = new JavaTickFixture()) {
            var world = f.player.compensatedWorld;
            var entities = f.player.compensatedEntities;
            var chunk = world.getChunk(0, 1);
            world.removeChunkLater(0, 1, 0);
            entities.addEntity(100, EntityTypeIds.ZOMBIE, new Vec3(0.5, 80, 20.5), 0, 0, 0);
            entities.addEntity(101, EntityTypeIds.ZOMBIE, new Vec3(0.5, 16, 20.5), 0, 0, 0);
            entities.addEntity(102, EntityTypeIds.ZOMBIE, new Vec3(0.5, 80, 20.5), 0, 0, 0);
            entities.addEntity(103, EntityTypeIds.ZOMBIE, new Vec3(0.5, -1, 20.5), 0, 0, 0);
            assertEquals(List.of(), ticks(f));
            world.putChunk(0, 1, chunk);
            assertEquals(List.of(101, 100, 102, 103), ticks(f));
        }
    }

    /** Player#isAlwaysTicking bypasses chunk visibility, including when a retained player leaves a loaded section. */
    @Test
    void playersInUnloadedChunksKeepTheirTickListPlace() throws Exception {
        try (var f = new JavaTickFixture()) {
            var entities = f.player.compensatedEntities;
            entities.addEntity(100, EntityTypeIds.PLAYER, new Vec3(0.5, 80, 20.5), 0, 0, 0);
            long order = entities.getEntity(100).getClientTickOrder();
            f.player.compensatedWorld.removeChunkLater(0, 1, 0);
            entities.addEntity(101, EntityTypeIds.ZOMBIE, new Vec3(0.5, 80, 0.5), 0, 0, 0);
            assertEquals(order, entities.getEntity(100).getClientTickOrder());
            assertEquals(List.of(100, 101), ticks(f));
        }
    }

    private static List<Integer> ticks(JavaTickFixture f) {
        List<Integer> result = new ArrayList<>();
        f.player.compensatedEntities.tickClientEntities(entity -> result.add(entity.getEntityId()));
        return result;
    }

    private static void positionSync(JavaTickFixture f, int id, Vec3 at) {
        var packet = new ClientboundEntityPositionSync(
                id, new PositionPath.Linear(new Vec3d(at.x, at.y, at.z)), 0, 0, false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_POSITION_SYNC, packet, false);
        f.player.packetEntityReplication.onEntityPositionSync(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }
}
