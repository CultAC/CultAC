package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import com.google.protobuf.CodedOutputStream;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

/** A section's {@code blocks} are the vanilla network write of its block states, without count or biomes. */
class SectionBytesTest {
    @Test
    void uniformAirIsZeroBitsAndItsStateAlone() {
        assertArrayEquals(new byte[] {0, 0}, SectionBytes.of(CachedSection.createAirSection()));
        assertArrayEquals(new byte[] {0, 0}, SectionBytes.of(null));
    }

    @Test
    void twoStatesAreFourBitsTheirPaletteAndAFixedDataArray() {
        CachedSection section = CachedSection.createAirSection();
        int stone = SyntheticTerrain.STONE;
        section.setStateId(CachedChunk.index(3, 5, 7), stone);

        byte[] bytes = SectionBytes.of(section);

        ByteBuffer expected = ByteBuffer.allocate(3 + CodedOutputStream.computeUInt32SizeNoTag(stone) + 256 * 8);
        expected.put((byte) 4).put((byte) 2).put((byte) 0);
        writeVarInt(expected, stone);
        int index = CachedChunk.index(3, 5, 7);
        long[] data = new long[256];
        data[index / 16] |= 1L << (index % 16) * 4;
        for (long word : data) expected.putLong(word);
        assertEquals(expected.capacity(), bytes.length);
        assertArrayEquals(expected.array(), bytes);
    }

    private static void writeVarInt(ByteBuffer out, int value) {
        while ((value & ~0x7F) != 0) {
            out.put((byte) (value & 0x7F | 0x80));
            value >>>= 7;
        }
        out.put((byte) value);
    }
}
