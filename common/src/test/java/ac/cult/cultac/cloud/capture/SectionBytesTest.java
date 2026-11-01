package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import com.google.protobuf.CodedOutputStream;
import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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

    /**
     * Thread bytes and cpu per section record, its blocks copied out first against written once; runs only when
     * {@code CULTAC_SECTION_BENCH} names a file the medians are appended to.
     */
    @Test
    void recordCost() throws IOException {
        String out = System.getenv("CULTAC_SECTION_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        List<CachedSection> sections = sections(new Random(13));
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        int reps = 15;
        int passes = 2_000;
        double[][] alloc = new double[2][reps];
        double[][] cpu = new double[2][reps];
        long sink = 0;
        for (int rep = -5; rep < reps; rep++) {
            for (int path = 0; path < 2; path++) {
                int way = (rep & 1) == 0 ? path : 1 - path;
                long bytes = threads.getThreadAllocatedBytes(thread);
                long time = threads.getCurrentThreadCpuTime();
                for (int pass = 0; pass < passes; pass++) {
                    for (CachedSection section : sections) {
                        byte[] record = way == 0
                                ? IntakeWire.section(pass, 4, -pass, SectionBytes.of(section))
                                : SectionBytes.record(pass, 4, -pass, section);
                        sink += record.length;
                    }
                }
                if (rep < 0) continue;
                double records = (double) passes * sections.size();
                alloc[way][rep] = (threads.getThreadAllocatedBytes(thread) - bytes) / records;
                cpu[way][rep] = (threads.getCurrentThreadCpuTime() - time) / records;
            }
        }
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "section record (sink %d):", sink));
        for (int way = 0; way < 2; way++) {
            Arrays.sort(alloc[way]);
            Arrays.sort(cpu[way]);
            line.append(String.format(
                    Locale.ROOT,
                    " %s alloc median %.0f B (min %.0f, max %.0f), cpu median %.0f ns (min %.0f, max %.0f);",
                    way == 0 ? "blocks then record" : "record",
                    alloc[way][reps / 2],
                    alloc[way][0],
                    alloc[way][reps - 1],
                    cpu[way][reps / 2],
                    cpu[way][0],
                    cpu[way][reps - 1]));
        }
        Files.writeString(
                Path.of(out), line.append('\n').toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
