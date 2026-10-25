package ac.cult.cultac.cloud.diag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.BlockGrid;
import com.github.luben.zstd.ZstdCompressCtx;
import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A tick's frame is the Zstandard frame of its text's UTF-8 bytes, whatever the text holds and whatever came before.
 * With {@code CULTAC_DIAG_TEXTS} naming a directory, every {@code .txt} under it is checked as a state too; with
 * {@code CULTAC_DIAG_TEXT_BENCH} naming a file, the bytes and cpu per frame against a string per text are appended.
 */
class DiagTextTest {
    private static final int LEVEL = 1;

    @Test
    void framesMatchTheTextsStringBytes() throws ReflectiveOperationException {
        BlockGrid grid = grid(new Random(7), -3, 60, -3, 2, 66, 2);
        String ascii = superDebug(new Random(1), 20_000);
        String huge = superDebug(new Random(2), DiagText.KEPT_CHARS + 10);
        List<String> states = List.of(
                ascii,
                "",
                "x",
                ascii.substring(0, 100),
                "café " + ascii,
                ascii,
                "Δv " + ascii,
                ascii + "𝄞",
                "lone high \ud834 then text",
                "lone low \udd1e then text",
                "ends high \ud834",
                huge,
                ascii.substring(0, 5_000),
                huge.substring(0, DiagText.KEPT_CHARS),
                ascii);
        try (DiagText text = new DiagText(LEVEL);
                ZstdCompressCtx zstd = new ZstdCompressCtx().setLevel(LEVEL)) {
            for (int round = 0; round < 2; round++) {
                for (String state : states) {
                    for (BlockGrid withGrid : Arrays.asList(null, grid)) {
                        String expected = text(state, withGrid);
                        assertArrayEquals(
                                zstd.compress(expected.getBytes(StandardCharsets.UTF_8)),
                                text.compress(state, withGrid));
                        assertEquals(expected.length(), text.length());
                    }
                }
            }
        }
    }

    @Test
    void recordedTextsFrameAsTheirStrings() throws IOException, ReflectiveOperationException {
        List<String> texts = recorded();
        assumeTrue(!texts.isEmpty());
        BlockGrid grid = grid(new Random(3), -120, -64, 998, -115, -59, 1003);
        try (DiagText text = new DiagText(LEVEL);
                ZstdCompressCtx zstd = new ZstdCompressCtx().setLevel(LEVEL)) {
            for (String state : texts) {
                for (BlockGrid withGrid : Arrays.asList(null, grid)) {
                    assertArrayEquals(
                            zstd.compress(text(state, withGrid).getBytes(StandardCharsets.UTF_8)),
                            text.compress(state, withGrid));
                }
            }
        }
    }

    @Test
    void costPerFrame() throws IOException, ReflectiveOperationException {
        String out = System.getenv("CULTAC_DIAG_TEXT_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        List<String> texts = recorded();
        if (texts.isEmpty()) {
            Random random = new Random(4);
            for (int i = 0; i < 200; i++) texts.add(superDebug(random, 12_000 + random.nextInt(30_000)));
        }
        BlockGrid grid = grid(new Random(5), -3, 60, -3, 2, 66, 2);
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        int reps = 15;
        double[][] alloc = new double[2][reps];
        double[][] cpu = new double[2][reps];
        long sink = 0;
        try (DiagText text = new DiagText(LEVEL);
                ZstdCompressCtx zstd = new ZstdCompressCtx().setLevel(LEVEL)) {
            for (int rep = -5; rep < reps; rep++) {
                for (int path = 0; path < 2; path++) {
                    int way = (rep & 1) == 0 ? path : 1 - path;
                    long bytes = threads.getThreadAllocatedBytes(thread);
                    long time = threads.getCurrentThreadCpuTime();
                    for (String state : texts) {
                        byte[] frame = way == 0
                                ? zstd.compress(text(state, grid).getBytes(StandardCharsets.UTF_8))
                                : text.compress(state, grid);
                        sink += frame.length;
                    }
                    if (rep < 0) continue;
                    alloc[way][rep] = (threads.getThreadAllocatedBytes(thread) - bytes) / (double) texts.size();
                    cpu[way][rep] = (threads.getCurrentThreadCpuTime() - time) / (double) texts.size();
                }
            }
        }
        StringBuilder line = new StringBuilder();
        line.append(String.format(Locale.ROOT, "diag frame, %d texts (sink %d):", texts.size(), sink));
        for (int way = 0; way < 2; way++) {
            Arrays.sort(alloc[way]);
            Arrays.sort(cpu[way]);
            line.append(String.format(
                    Locale.ROOT,
                    " %s alloc median %.0f B (min %.0f, max %.0f), cpu median %.0f ns (min %.0f, max %.0f);",
                    way == 0 ? "string" : "kept",
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

    /** The text as a string of its own, sized as the state and the grid's text. */
    private static String text(String state, BlockGrid grid) {
        StringBuilder sb = new StringBuilder(state.length() + (grid == null ? 0 : grid.textLength()));
        sb.append(state);
        if (grid != null) grid.appendTo(sb);
        return sb.toString();
    }

    private static List<String> recorded() throws IOException {
        List<String> texts = new ArrayList<>();
        String dir = System.getenv("CULTAC_DIAG_TEXTS");
        if (dir == null || dir.isEmpty()) return texts;
        try (Stream<Path> files = Files.walk(Path.of(dir))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".txt"))
                    .sorted()
                    .toList()) {
                texts.add(Files.readString(file));
            }
        }
        return texts;
    }

    private static String superDebug(Random random, int chars) {
        StringBuilder sb = new StringBuilder(chars + 200);
        while (sb.length() < chars) {
            sb.append("Starting vel: PredVector{x=")
                    .append(random.nextDouble() - 0.5)
                    .append(", y=")
                    .append(random.nextDouble() - 0.5)
                    .append(", z=")
                    .append(random.nextDouble() - 0.5)
                    .append(", reason='Inertia threshold', isJump=")
                    .append(random.nextBoolean())
                    .append("}\n");
        }
        sb.setLength(chars);
        return sb.toString();
    }

    private static BlockGrid grid(Random random, int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
            throws ReflectiveOperationException {
        Constructor<BlockGrid> constructor = BlockGrid.class.getDeclaredConstructor(
                int.class, int.class, int.class, int.class, int.class, int.class, int[].class);
        constructor.setAccessible(true);
        int stateCount = DataTables.defaults().registry().stateCount();
        int[] states = new int[(maxY - minY + 1) * (maxZ - minZ + 1) * (maxX - minX + 1)];
        for (int i = 0; i < states.length; i++) states[i] = random.nextInt(stateCount);
        return constructor.newInstance(minX, minY, minZ, maxX, maxY, maxZ, states);
    }
}
