package ac.cult.cultac.cloud.diag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.BlockGrid;
import ac.cult.cultac.utils.debug.DebugText;
import ac.cult.cultac.utils.math.Vec3;
import com.github.luben.zstd.ZstdCompressCtx;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A tick's frame is the Zstandard frame of its text's UTF-8 bytes, whatever the text holds and whatever came before.
 * With {@code CULTAC_DIAG_TEXTS} naming a directory, every {@code .txt} under it is checked as a state too.
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
                                text.compress(kept(state), withGrid));
                        assertEquals(expected.length(), text.length());
                    }
                }
            }
        }
    }

    @Test
    void aRecordedStateFramesAsItsPrintedString() throws ReflectiveOperationException {
        BlockGrid grid = grid(new Random(8), -3, 60, -3, 2, 66, 2);
        Random random = new Random(9);
        List<DebugText> states = new ArrayList<>();
        for (int chars : new int[] {0, 1, 300, 20_000, DiagText.KEPT_CHARS - 50, DiagText.KEPT_CHARS + 10, 20_000}) {
            states.add(recorded(random, chars, ""));
        }
        states.add(recorded(random, 5_000, "Δv 𝄞"));
        states.add(recorded(random, 5_000, "lone high \ud834"));
        states.add(recorded(random, 5_000, ""));
        try (DiagText text = new DiagText(LEVEL);
                ZstdCompressCtx zstd = new ZstdCompressCtx().setLevel(LEVEL)) {
            for (DebugText state : states) {
                for (BlockGrid withGrid : Arrays.asList(null, grid)) {
                    String expected = text(state.toString(), withGrid);
                    assertArrayEquals(
                            zstd.compress(expected.getBytes(StandardCharsets.UTF_8)), text.compress(state, withGrid));
                    assertEquals(expected.length(), text.length());
                }
            }
        }
    }

    /** About {@code chars} of SuperDebug-like text appended as strings, numbers and vectors, then {@code tail}. */
    private static DebugText recorded(Random random, int chars, String tail) {
        DebugText.Builder sb = new DebugText.Builder();
        int length = 0;
        while (length < chars) {
            Vec3 vector = new Vec3(random.nextDouble() - 0.5, random.nextGaussian(), random.nextInt(100) / 16.0);
            float yaw = random.nextFloat() * 360;
            long time = random.nextLong();
            sb.append("\nStarting vel: ")
                    .append(vector)
                    .append(" yaw=")
                    .append(yaw)
                    .append(" at ")
                    .append(time)
                    .append(' ')
                    .append(random.nextBoolean());
            length += 40
                    + vector.toString().length()
                    + Float.toString(yaw).length()
                    + Long.toString(time).length();
        }
        return sb.append(tail).toText();
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
                            text.compress(kept(state), withGrid));
                }
            }
        }
    }

    /** {@code state} as a text kept as its string, as a watch keeps a printed state. */
    static DebugText kept(String state) {
        return new DebugText.Builder().append(state).toText();
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
