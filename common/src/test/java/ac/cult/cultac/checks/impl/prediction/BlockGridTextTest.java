package ac.cult.cultac.checks.impl.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.DataTables;
import java.lang.reflect.Constructor;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The block grid text stays identical to the text the triage tooling parses, and its length is known up front. */
class BlockGridTextTest {
    @Test
    void textMatchesTheOriginalLayout() throws ReflectiveOperationException {
        Constructor<BlockGrid> constructor = BlockGrid.class.getDeclaredConstructor(
                int.class, int.class, int.class, int.class, int.class, int.class, int[].class);
        constructor.setAccessible(true);
        int stateCount = DataTables.defaults().registry().stateCount();
        int[] palette = {0, 1, 9, stateCount / 3, stateCount / 2, stateCount - 1};
        int[][] bounds = {
            {-3, 60, -3, 2, 66, 2}, {-120, -64, 998, -115, -59, 1003}, {0, 0, 0, 0, 0, 0}, {9, 318, -10, 14, 320, -5}
        };
        Random random = new Random(0x6b67L);
        for (int[] b : bounds) {
            for (int round = 0; round < 8; round++) {
                int[] states = new int[(b[4] - b[1] + 1) * (b[5] - b[2] + 1) * (b[3] - b[0] + 1)];
                for (int i = 0; i < states.length; i++) {
                    states[i] = round == 0 ? palette[0] : palette[random.nextInt(palette.length)];
                }
                BlockGrid grid = constructor.newInstance(b[0], b[1], b[2], b[3], b[4], b[5], states);

                StringBuilder sb = new StringBuilder("prefix\n");
                grid.appendTo(sb);

                assertEquals("prefix\n" + original(b, states), sb.toString());
                assertEquals(sb.length() - "prefix\n".length(), grid.textLength());
            }
        }
    }

    /** The grid text as the per-cell name array printed it. */
    private static String original(int[] b, int[] states) {
        int minX = b[0], minY = b[1], minZ = b[2], maxX = b[3], maxY = b[4], maxZ = b[5];
        StringBuilder sb = new StringBuilder();
        int maxPosLength = Math.max(coordinateWidth(minX), coordinateWidth(maxX));
        maxPosLength = Math.max(maxPosLength, Math.max(coordinateWidth(minZ), coordinateWidth(maxZ)));
        int xSize = maxX - minX + 1;
        int zSize = maxZ - minZ + 1;
        String[] nearbyBlocks = new String[states.length];
        int maxLength = 0;
        for (int i = 0; i < states.length; i++) {
            String text =
                    DataTables.defaults().registry().debugString(states[i]).replace("minecraft:", "");
            nearbyBlocks[i] = text;
            maxLength = Math.max(text.length(), maxLength);
        }
        maxPosLength += 4;
        maxLength++;
        for (int y = maxY; y >= minY; y--) {
            sb.append("y: ").append(y).append("\n");
            appendPadded(sb, "x: ", maxPosLength);
            for (int x = minX; x <= maxX; x++) appendPadded(sb, Integer.toString(x), maxLength);
            sb.append("\n");
            for (int z = minZ; z <= maxZ; z++) {
                appendPadded(sb, "z: " + z + " ", maxPosLength);
                for (int x = minX; x <= maxX; x++) {
                    appendPadded(sb, nearbyBlocks[((y - minY) * zSize + z - minZ) * xSize + x - minX], maxLength);
                }
                sb.append("\n");
            }
            sb.append("\n\n\n");
        }
        return sb.toString();
    }

    private static int coordinateWidth(int coordinate) {
        return (int) Math.ceil(Math.log10(Math.abs(coordinate)));
    }

    private static void appendPadded(StringBuilder sb, String value, int width) {
        sb.append(value);
        for (int i = value.length(); i < width; i++) sb.append(' ');
    }
}
