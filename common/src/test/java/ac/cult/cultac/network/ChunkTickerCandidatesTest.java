package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.CompensatedGeysers;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The world reader keeps exactly the ticker candidates whose received block has a ticker, in candidate order, and a
 * column without candidates leaves none of the column's earlier tickers.
 */
class ChunkTickerCandidatesTest {
    private static final int SECTIONS = 24, MIN_Y = -64;

    @Test
    void theColumnKeepsTheCandidatesWhoseBlockTicks() throws Exception {
        int ticking = -1, dry = -1;
        for (int state = 0; state < DataTables.defaults().registry().stateCount(); state++) {
            if (!BlockIds.is(state, BlockIds.POTENT_SULFUR)) continue;
            if (CompensatedGeysers.hasTicker(state)) ticking = ticking < 0 ? state : ticking;
            else dry = dry < 0 ? state : dry;
        }
        assertTrue(ticking > 0 && dry > 0, ticking + " " + dry);
        var random = new SplittableRandom(0x71C4L);
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3, true)) {
            var player = fixture.player;
            player.compensatedWorld.setLastClientboundDimension(
                    "minecraft:overworld",
                    player.getWorldRegistries().dimension("minecraft:overworld").dimension());
            int kept = 0;
            for (int round = 0; round < 40; round++) {
                int chunkX = random.nextInt(-3, 3), chunkZ = random.nextInt(-3, 3);
                int[] sections = new int[SECTIONS];
                for (int i = 0; i < SECTIONS; i++) {
                    int kind = random.nextInt(4);
                    sections[i] = kind == 0 ? ticking : kind == 1 ? dry : 0;
                }
                var candidates = new ArrayList<BlockPos>();
                for (int i = 0, count = round % 4 == 0 ? 0 : random.nextInt(1, 12); i < count; i++)
                    candidates.add(new BlockPos(
                            chunkX * 16 + random.nextInt(16),
                            MIN_Y + random.nextInt(SECTIONS * 16),
                            chunkZ * 16 + random.nextInt(16)));
                if (!candidates.isEmpty()) candidates.add(candidates.getFirst());
                var expected = new LinkedHashMap<Long, BlockPos>();
                for (var position : candidates)
                    if (sections[(position.getY() - MIN_Y) >> 4] == ticking) expected.put(position.asLong(), position);
                kept += expected.size();
                apply(fixture, new WorldPackets.Chunk(chunkX, chunkZ, column(sections), candidates, List.of(), null));
                int minX = chunkX * 16, minZ = chunkZ * 16;
                assertEquals(
                        List.copyOf(expected.values()),
                        player.compensatedWorld
                                .getGeysers()
                                .getTickersInOrder(minX, MIN_Y, minZ, minX + 15, MIN_Y + SECTIONS * 16, minZ + 15),
                        "round " + round);
            }
            assertTrue(kept > 40, "kept " + kept);
        }
    }

    /** The world reader on {@code chunk}, its pings answered. */
    @SuppressWarnings("unchecked")
    private static void apply(RecordReceiveFixture fixture, WorldPackets.Chunk chunk) {
        var player = fixture.player;
        var type = (PacketType<WorldPackets.Chunk>)
                fixture.registrar.runtime().typeForKey("clientbound.level_chunk_with_light");
        var event = new PacketSendEvent<>(fixture.user, ConnectionPhase.PLAY, type, chunk, false);
        new PacketWorldReaderTwentySix().handleMapChunk(player, event, chunk);
        event.tasksAfter().forEach(Runnable::run);
        for (int id = player.lastTransactionReceived.get() + 1; id <= player.lastTransactionSent.get(); id++) {
            player.lastTransactionReceived.set(id);
            player.latencyUtils.handleNettySyncTransaction(id);
        }
    }

    /** Pinned 26.3 section bytes, each section single-entry of its state and biome 0. */
    private static ByteBuffer column(int[] states) {
        ByteBuf out = Unpooled.buffer();
        for (int state : states) {
            out.writeShort(state == 0 ? 0 : 4096).writeShort(0);
            out.writeByte(0);
            Wire.writeVarInt(out, state);
            out.writeByte(0);
            Wire.writeVarInt(out, 0);
        }
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        out.release();
        return ByteBuffer.wrap(bytes);
    }
}
