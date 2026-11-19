package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One remove_entities packet costs about the same per id however many entities are tracked or in which order. */
class JavaBulkEntityRemovalCostTest {
    @Test
    void aReversedRemovalOfManyEntitiesIsNotQuadratic() throws Exception {
        try (var f = new JavaTickFixture()) {
            var entities = f.player.compensatedEntities;
            int count = 30_000;
            for (int i = 0; i < count; i++)
                entities.addEntity(10_000 + i, EntityTypeIds.ZOMBIE, new Vec3(4.5, 64, 4.5), 0, 0, 0);
            List<Integer> ids = new ArrayList<>(count);
            // The newest first: a List#remove per id scans every entity left before it.
            for (int i = count - 1; i >= 0; i--) ids.add(10_000 + i);
            var packet = new ClientboundRemoveEntities(ids);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_ENTITIES, packet, false);
            long start = System.nanoTime();
            f.player.packetEntityReplication.onRemoveEntities(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            long elapsed = System.nanoTime() - start;
            assertEquals(0, entities.entityMap.size());
            // One scan per id took about a second; one pass takes a few milliseconds.
            assertTrue(elapsed < 200_000_000L, "removing " + count + " took " + elapsed + " ns");
        }
    }
}
