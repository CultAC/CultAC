package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import com.google.protobuf.CodedOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
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

    @Test
    void aRecordIsTheSectionRecordOfItsBlocks() {
        int[][] positions = {{0, 0, 0}, {-1, -4, 1}, {1_874_999, 19, -1_874_999}, {-63, -64, 127}};
        for (CachedSection section : sections(new Random(11))) {
            for (int[] at : positions) {
                assertArrayEquals(
                        IntakeWire.section(at[0], at[1], at[2], SectionBytes.of(section)),
                        SectionBytes.record(at[0], at[1], at[2], section));
            }
        }
    }

    @Test
    void aSectionRecordReadsOnlyItsRange() {
        for (CachedSection section : sections(new Random(12))) {
            byte[] blocks = SectionBytes.of(section);
            byte[] padded = new byte[blocks.length + 9];
            Arrays.fill(padded, (byte) 0x5A);
            System.arraycopy(blocks, 0, padded, 4, blocks.length);
            assertArrayEquals(
                    IntakeWire.section(-7, 3, 9, blocks), IntakeWire.section(-7, 3, 9, padded, 4, blocks.length));
        }
    }

    /** No section, uniform ones, and palettes from two states to the registry's. */
    private static List<CachedSection> sections(Random random) {
        int stateCount = DataTables.defaults().registry().stateCount();
        List<CachedSection> sections = new ArrayList<>();
        sections.add(null);
        sections.add(CachedSection.createAirSection());
        for (int states : new int[] {1, 2, 5, 16, 17, 256, 300, 4096}) {
            int[] palette = new int[states];
            for (int i = 0; i < states; i++) palette[i] = random.nextInt(stateCount);
            CachedSection section = CachedSection.createAirSection();
            for (int index = 0; index < 4096; index++) {
                section.setStateId(index, palette[states == 1 ? 0 : random.nextInt(states)]);
            }
            sections.add(section);
        }
        return sections;
    }

    private static void writeVarInt(ByteBuffer out, int value) {
        while ((value & ~0x7F) != 0) {
            out.put((byte) (value & 0x7F | 0x80));
            value >>>= 7;
        }
        out.put((byte) value);
    }
}
