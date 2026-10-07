package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MotionHeightmapValuesTest {
    @Test
    void enumHeightmapsRetainOnlyRainCoverAndKeepTheNextFieldAligned() {
        var input = Unpooled.buffer();
        try {
            Wire.writeVarInt(input, 3);
            for (int type : new int[] {1, 4, 5}) {
                Wire.writeVarInt(input, type);
                Wire.writeVarInt(input, 2);
                input.writeLong(type * 10L);
                input.writeLong(type * 10L + 1);
            }
            input.writeInt(123456);
            assertArrayEquals(new long[] {40, 41}, MotionHeightmapValues.read(input, ProtocolVersion.V26_3));
            assertEquals(123456, input.readInt());
            assertFalse(input.isReadable());
        } finally {
            input.release();
        }
    }

    @Test
    void olderNbtSkipsUnrelatedDataAndTheChunkOwnsItsHeightArray() {
        var input = Unpooled.buffer();
        try {
            input.writeBytes(BinaryNbt.write(new NbtValue.Compound(Map.of(
                    "MOTION_BLOCKING", new NbtValue.PrimitiveArray(NbtValue.Kind.LONG_ARRAY, List.of(12L, 34L)),
                    "WORLD_SURFACE", new NbtValue.PrimitiveArray(NbtValue.Kind.LONG_ARRAY, List.of(56L)),
                    "unused", new NbtValue.Compound(Map.of("text", new NbtValue.Text("skip".repeat(1000))))))));
            input.writeByte(0);
            input.writeInt(987654);
            long[] received = MotionHeightmapValues.read(input, ProtocolVersion.V1_21_4);
            assertArrayEquals(new long[] {12, 34}, received);
            assertNull(MotionHeightmapValues.read(input, ProtocolVersion.V1_21_4));
            assertEquals(987654, input.readInt());
            var chunk = new WorldPackets.Chunk(0, 0, ByteBuffer.allocate(0), List.of(), null, List.of(), received);
            received[0] = 99;
            chunk.motionBlocking()[1] = 99;
            assertArrayEquals(new long[] {12, 34}, chunk.motionBlocking());
            assertNull(new WorldPackets.Chunk(0, 0, ByteBuffer.allocate(0), List.of()).motionBlocking());
        } finally {
            input.release();
        }
    }
}
