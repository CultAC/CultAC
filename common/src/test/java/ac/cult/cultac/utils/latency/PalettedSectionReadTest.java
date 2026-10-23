package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class PalettedSectionReadTest {
    private static final int REGISTRY = 30_000;

    @Test
    void contiguousAndSplitBuffersReadTheSameWordsAndUniformityMatchesEveryIndex() {
        var random = new SplittableRandom(0x4EADL);
        for (int round = 0; round < 4000; round++) {
            int bits = new int[] {0, 4, 5, 6, 7, 8, 15}[random.nextInt(7)];
            int paletteSize = bits == 0 || bits == 15 ? 0 : 1 + random.nextInt(1 << bits);
            var wire = Unpooled.buffer();
            wire.writeByte(bits);
            if (bits == 0) Wire.writeVarInt(wire, random.nextInt(REGISTRY));
            else if (bits != 15) {
                Wire.writeVarInt(wire, paletteSize);
                for (int i = 0; i < paletteSize; i++) Wire.writeVarInt(wire, random.nextInt(REGISTRY));
            }
            if (bits != 0) {
                int perWord = 64 / bits, range = bits == 15 ? REGISTRY : paletteSize;
                int first = random.nextInt(range);
                boolean uniform = random.nextInt(3) == 0;
                int index = 0;
                for (int word = 0; word < (4096 + perWord - 1) / perWord; word++) {
                    long value = perWord * bits == 64 ? 0 : random.nextLong() & -(1L << (perWord * bits));
                    for (int slot = 0; slot < perWord; slot++) {
                        boolean stored = index + slot < 4096;
                        int code = uniform && stored && index + slot != 4095 - round % 7 ? first : random.nextInt(range);
                        value |= (long) code << (slot * bits);
                    }
                    index += perWord;
                    wire.writeLong(value);
                }
            }
            byte[] bytes = new byte[wire.readableBytes()];
            wire.readBytes(bytes);
            int split = random.nextInt(bytes.length + 1);
            var contiguous = PalettedSection.readBlocks(Unpooled.wrappedBuffer(bytes), ProtocolVersion.V26_3, REGISTRY);
            var parts = Unpooled.wrappedBuffer(
                    Unpooled.wrappedBuffer(bytes, 0, split), Unpooled.wrappedBuffer(bytes, split, bytes.length - split));
            var composite = PalettedSection.readBlocks(parts, ProtocolVersion.V26_3, REGISTRY);
            assertArrayEquals(written(contiguous), written(composite), "round " + round);
            boolean expected = true;
            for (int i = 1; i < 4096; i++) expected &= contiguous.get(i) == contiguous.get(0);
            assertEquals(expected, contiguous.isUniform(), "round " + round);
        }
    }

    @Test
    void truncatedWordsFailAsTheyDidLongByLong() {
        var wire = Unpooled.buffer();
        wire.writeByte(4);
        Wire.writeVarInt(wire, 2);
        Wire.writeVarInt(wire, 1);
        Wire.writeVarInt(wire, 2);
        for (int i = 0; i < 255; i++) wire.writeLong(i);
        wire.writeInt(7);
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> PalettedSection.readBlocks(wire, ProtocolVersion.V26_3, REGISTRY));
        assertEquals(4 + 255 * Long.BYTES, wire.readerIndex());
    }

    private static byte[] written(PalettedSection section) {
        ByteBuf output = Unpooled.buffer();
        section.write(output);
        byte[] bytes = new byte[output.readableBytes()];
        output.readBytes(bytes);
        return bytes;
    }
}
