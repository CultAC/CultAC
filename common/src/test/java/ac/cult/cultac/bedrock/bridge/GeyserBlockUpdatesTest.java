package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockChangeEntry;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateSubChunkBlocksPacket;
import org.geysermc.geyser.registry.type.GeyserBedrockBlock;
import org.geysermc.geyser.session.GeyserSession;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class GeyserBlockUpdatesTest {
    @BeforeClass public static void bootstrap() { OfflineCultTestBootstrap.installConfig(); }

    @Test public void asynchronousQueueCapturesOnlyAckCorrectionsAndWaitsForReceipt() throws Exception {
        Harness h = new Harness();
        var session = mock(GeyserSession.class);
        List<BedrockPacket> queue = new ArrayList<>();
        doAnswer(call -> { queue.add(call.getArgument(0)); return null; }).when(session).sendUpstreamPacket(any());
        session.sendUpstreamPacket(block(0, 17)); // Normal translation remains on the Java listener.
        GeyserBlockAckTranslator.bracket(session, () -> {
            session.sendUpstreamPacket(block(0, 99));
            session.sendUpstreamPacket(block(1, 99));
        });
        session.sendUpstreamPacket(block(0, 17)); // Later unrelated packet must not overwrite correction.
        assertFalse(h.corrections.active()); // Translation has finished, queue has not drained yet.
        for (BedrockPacket packet : queue) {
            if (packet instanceof GeyserBlockAckTranslator.Boundary marker) {
                if (marker.start) h.corrections.begin(); else h.end();
            } else h.capture(packet);
        }
        assertFalse(h.corrections.active());
        assertEquals(1, h.receipts.size());
        verifyZeroInteractions(h.player.compensatedWorld);
        h.receipts.removeFirst().accept(h.player);
        verify(h.player.compensatedWorld).handleServerBlockUpdate(new BlockPos(2, 64, 3), Blocks.AIR.defaultBlockState(), 0);
        verifyNoMoreInteractions(h.player.compensatedWorld);
    }

    @Test public void scopeAlwaysEndsEvenWhenTranslationThrows() {
        var session = mock(GeyserSession.class);
        List<BedrockPacket> queue = new ArrayList<>();
        doAnswer(call -> { queue.add(call.getArgument(0)); return null; }).when(session).sendUpstreamPacket(any());
        assertThrows(IllegalStateException.class, () -> GeyserBlockAckTranslator.bracket(session,
                () -> { throw new IllegalStateException("translation failed"); }));
        assertEquals(2, queue.size());
        assertTrue(((GeyserBlockAckTranslator.Boundary) queue.getFirst()).start);
        assertFalse(((GeyserBlockAckTranslator.Boundary) queue.getLast()).start);
    }

    @Test public void layersAreCombinedForOneAckAndForgottenBeforeTheNext() throws Exception {
        Harness h = new Harness();
        h.corrections.begin();
        h.capture(block(0, 104));
        h.capture(block(1, 101));
        h.end();
        h.receipts.removeFirst().accept(h.player);
        verify(h.player.compensatedWorld).handleServerBlockUpdate(new BlockPos(2, 64, 3),
                Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true), 0);
        h.corrections.begin();
        h.capture(block(0, 101));
        h.capture(block(1, 99));
        h.end();
        h.receipts.removeFirst().accept(h.player);
        verify(h.player.compensatedWorld).handleServerBlockUpdate(new BlockPos(2, 64, 3), Blocks.WATER.defaultBlockState(), 0);
    }

    @Test public void batchPositionsUseCapturedOriginAndSnapshotBeforePacketReuse() throws Exception {
        Harness h = new Harness();
        h.player.x = 1827;
        h.player.z = -3384;
        var packet = new UpdateSubChunkBlocksPacket();
        packet.setPosition(Vector3i.from(1824, 64, -432));
        var pos = Vector3i.from(1827, 65, -424);
        packet.getStandardBlocks().add(new BlockChangeEntry(pos, definition(17), 3, -1, BlockChangeEntry.MessageType.NONE));
        packet.getExtraBlocks().add(new BlockChangeEntry(pos, definition(99), 3, -1, BlockChangeEntry.MessageType.NONE));
        h.corrections.begin();
        h.corrections.capture(packet, new BedrockCoordinateFrame(0, -2960, 1), h.palette);
        h.end();
        packet.getStandardBlocks().clear();
        packet.getExtraBlocks().clear();
        h.receipts.removeFirst().accept(h.player);
        verify(h.player.compensatedWorld).handleServerBlockUpdate(new BlockPos(1827, 65, -3384), Blocks.SAND.defaultBlockState(), 0);
    }

    @Test public void emptyAndNestedScopesDoNotGenerateExtraTransactions() throws Exception {
        Harness h = new Harness();
        h.corrections.begin(); h.end();
        assertTrue(h.receipts.isEmpty());
        h.corrections.begin(); h.corrections.begin();
        h.capture(block(0, 17)); h.capture(block(1, 99));
        h.end();
        assertTrue(h.receipts.isEmpty());
        h.end();
        assertEquals(1, h.receipts.size());
    }

    private static GeyserBedrockBlock definition(int id) { return new GeyserBedrockBlock(id, NbtMap.EMPTY); }
    private static UpdateBlockPacket block(int layer, int id) {
        var packet = new UpdateBlockPacket();
        packet.setBlockPosition(Vector3i.from(2, 64, 3));
        packet.setDataLayer(layer);
        packet.setDefinition(definition(id));
        return packet;
    }
    private static final class Harness {
        final CultPlayer player = mock(CultPlayer.class);
        final GeyserBlockUpdates corrections = new GeyserBlockUpdates();
        final Map<Integer, BlockState> palette = Map.of(17, Blocks.SAND.defaultBlockState(),
                99, Blocks.AIR.defaultBlockState(), 101, Blocks.WATER.defaultBlockState(), 104, Blocks.OAK_STAIRS.defaultBlockState());
        final List<Consumer<CultPlayer>> receipts = new ArrayList<>();
        Harness() throws Exception {
            player.compensatedWorld = mock(CompensatedWorld.class);
            player.y = 64;
            var received = CultPlayer.class.getField("lastTransactionReceived");
            received.setAccessible(true);
            received.set(player, new AtomicInteger());
        }
        void capture(BedrockPacket packet) { corrections.capture(packet, BedrockCoordinateFrame.IDENTITY, palette); }
        void end() { corrections.end(player, receipts::add); }
    }
}
