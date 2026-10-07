package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReceivedMotionHeightmapsTest {
    @Test
    void rainCoverFollowsReceivedHeightsBlockChangesAndTagGenerations() throws Exception {
        ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.installConfig();
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var world = player.compensatedWorld;
            var dimension = player.getWorldRegistries().dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
            world.setDimension("minecraft:overworld", dimension);
            world.ensureValidationChunkLoaded(0, 0);
            int bottom = world.getMinHeight();
            assertEquals(bottom, world.clientMotionBlockingHeightAt(8, 8));
            assertEquals(bottom, world.clientMotionBlockingHeightAt(40, 40));
            assertEquals(world.clientSeaLevel() + 1, world.clientMotionBlockingHeightAt(30000000, 8));
            var data = DataTables.defaults();
            int stone = data.registry().block("minecraft:stone").defaultState();
            int water = data.registry().block("minecraft:water").defaultState();
            world.applyBlockChangeRawDANGER(8, 64, 8, stone);
            world.applyBlockChangeRawDANGER(8, 80, 8, stone);
            world.applyBlockChangeRawDANGER(8, 90, 8, water);
            assertEquals(91, world.clientMotionBlockingHeightAt(8, 8));
            world.applyBlockChangeRawDANGER(8, 90, 8, 0);
            assertEquals(81, world.clientMotionBlockingHeightAt(8, 8));

            player.registryState = new ClientComponentRegistries();
            player.registryState.appendTags(new RegistryTags(Map.of("minecraft:block", RegistryTags.Payload.EMPTY)));
            world.applyBlockChangeRawDANGER(8, 96, 8, stone);
            assertEquals(81, world.clientMotionBlockingHeightAt(8, 8)); // Reloads do not re-prime old heights.
            world.applyBlockChangeRawDANGER(8, 80, 8, stone);
            assertEquals(81, world.clientMotionBlockingHeightAt(8, 8)); // Identical state is a client no-op.
            world.applyBlockChangeRawDANGER(8, 94, 8, water);
            assertEquals(95, world.clientMotionBlockingHeightAt(8, 8));
            world.applyBlockChangeRawDANGER(8, 94, 8, 0);
            assertEquals(bottom, world.clientMotionBlockingHeightAt(8, 8));

            int height = world.getMaxHeight() - bottom;
            int bits = 32 - Integer.numberOfLeadingZeros(height);
            long[] supplied = new long[(256 + 64 / bits - 1) / (64 / bits)];
            Arrays.fill(supplied, -1L);
            world.applyClientMotionHeightmap("minecraft:overworld", 0, 0, supplied);
            assertEquals(bottom + (1 << bits) - 1, world.clientMotionBlockingHeightAt(8, 8));
            supplied[0] = 0;
            assertEquals(bottom + (1 << bits) - 1, world.clientMotionBlockingHeightAt(0, 0));
            world.applyClientMotionHeightmap("minecraft:the_nether", 0, 0, new long[supplied.length]);
            assertEquals(bottom + (1 << bits) - 1, world.clientMotionBlockingHeightAt(8, 8));

            int transaction = player.lastTransactionReceived.get() + 1;
            var replacement = new CachedChunk(new CachedSection[world.getLastClientboundSectionCount()], transaction);
            world.addToCache(replacement, "minecraft:overworld", transaction, 0, 0, List.of());
            assertNotSame(replacement, world.getChunk(0, 0));
            player.latencyUtils.handleNettySyncTransaction(transaction);
            assertSame(replacement, world.getChunk(0, 0));
            assertEquals(bottom + (1 << bits) - 1, world.clientMotionBlockingHeightAt(8, 8));
            world.applyClientMotionHeightmap("minecraft:overworld", 0, 0, new long[0]);
            assertEquals(bottom + (1 << bits) - 1, world.clientMotionBlockingHeightAt(8, 8));
            world.applyClientMotionHeightmap("minecraft:overworld", 0, 0, new long[supplied.length]);
            assertEquals(bottom, world.clientMotionBlockingHeightAt(8, 8));

            Arrays.fill(supplied, -1L);
            var receivedChunk =
                    new CachedChunk(new CachedSection[world.getLastClientboundSectionCount()], transaction + 1);
            world.addToCache(receivedChunk, "minecraft:overworld", transaction + 1, 0, 0, List.of(), supplied);
            assertEquals(bottom, world.clientMotionBlockingHeightAt(8, 8));
            player.latencyUtils.handleNettySyncTransaction(transaction + 1);
            assertEquals(bottom + (1 << bits) - 1, world.clientMotionBlockingHeightAt(8, 8));
            var staleChunk = new CachedChunk(new CachedSection[world.getLastClientboundSectionCount()], transaction);
            world.addToCache(
                    staleChunk, "minecraft:overworld", transaction, 0, 0, List.of(), new long[supplied.length]);
            player.latencyUtils.handleNettySyncTransaction(transaction);
            assertSame(receivedChunk, world.getChunk(0, 0));
            assertEquals(bottom + (1 << bits) - 1, world.clientMotionBlockingHeightAt(8, 8));
        }
    }
}
