package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The world reader applies a chunk's received motion-blocking heights as the record gives them, and the record still
 * gives the received values after the reader and later block changes have run.
 */
class ChunkMotionBlockingValuesTest {
    private static final String LEVEL = "minecraft:overworld";

    @Test
    void theReaderAppliesTheReceivedHeightsAndLeavesTheRecordAsReceived() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        int stone = DataTables.defaults().registry().block("minecraft:stone").defaultState();
        var random = new SplittableRandom(77);
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var world = player.compensatedWorld;
            var dimension = player.getWorldRegistries().dimension(LEVEL);
            world.setLastClientboundDimension(LEVEL, dimension.dimension());
            world.setDimension(LEVEL, dimension);
            int bottom = world.getMinHeight();
            int bits = 32 - Integer.numberOfLeadingZeros(world.getMaxHeight() - bottom);
            int perWord = 64 / bits;
            int length = (256 + perWord - 1) / perWord;
            for (int round = 0; round < 40; round++) {
                int x = random.nextInt(-8, 8), z = random.nextInt(-8, 8);
                int kind = round % 4;
                long[] received = kind == 3 ? new long[length - 1] : new long[length];
                for (int column = 0; column < 256 && kind != 3; column++) {
                    long height = kind == 2 ? 0 : random.nextInt(0, world.getMaxHeight() - bottom + 1);
                    received[column / perWord] |= height << (column % perWord * bits);
                }
                if (kind == 1) received[random.nextInt(length)] ^= random.nextLong();
                long[] sent = received.clone();
                var chunk = new WorldPackets.Chunk(
                        x, z, airSections(world.getLastClientboundSectionCount()), List.of(), List.of(), received);
                received[0] = ~received[0];
                apply(fixture, chunk);
                for (int column = 0; column < 256; column++) {
                    int cx = column & 15, cz = column >> 4;
                    long expected =
                            kind == 3 ? 0 : sent[column / perWord] >>> (column % perWord * bits) & (1L << bits) - 1;
                    assertEquals(
                            bottom + expected,
                            world.clientMotionBlockingHeightAt((x << 4) + cx, (z << 4) + cz),
                            "round " + round + " column " + column);
                }
                for (int change = 0; change < 6; change++)
                    world.applyBlockChangeRawDANGER(
                            (x << 4) + random.nextInt(16),
                            random.nextInt(bottom, bottom + 200),
                            (z << 4) + random.nextInt(16),
                            random.nextBoolean() ? stone : 0);
                assertArrayEquals(sent, chunk.motionBlocking(), "round " + round);
                assertArrayEquals(sent, chunk.motionBlockingValues(), "round " + round);
            }
        }
    }

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

    private static ByteBuffer airSections(int count) {
        ByteBuf out = Unpooled.buffer();
        try {
            for (int i = 0; i < count; i++) {
                out.writeShort(0);
                out.writeShort(0);
                out.writeByte(0);
                Wire.writeVarInt(out, 0);
                out.writeByte(0);
                Wire.writeVarInt(out, 0);
            }
            return ByteBuffer.wrap(ByteBufUtil.getBytes(out));
        } finally {
            out.release();
        }
    }
}
