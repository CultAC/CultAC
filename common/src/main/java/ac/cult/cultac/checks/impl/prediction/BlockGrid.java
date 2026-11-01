package ac.cult.cultac.checks.impl.prediction;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.CultMath;

/**
 * The blocks around a player's bounding box, two blocks out, as {@link SuperDebug} prints them. The grid holds model
 * block-state ids, so a grid read on the player's packet thread may be printed on any other.
 */
public final class BlockGrid {
    /** Printed names by default-registry state id; a racing fill stores the same immutable name. */
    private static final String[] NAMES =
            new String[DataTables.defaults().registry().stateCount()];

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final int[] states;

    private BlockGrid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int[] states) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.states = states;
    }

    /** Reads the grid from the player's simulation world; on the player's packet thread. */
    public static BlockGrid around(CultPlayer player) {
        int minX = CultMath.floor(player.boundingBox.minX) - 2;
        int maxX = CultMath.ceil(player.boundingBox.maxX) + 2;
        int minY = CultMath.floor(player.boundingBox.minY) - 2;
        int maxY = CultMath.ceil(player.boundingBox.maxY) + 2;
        int minZ = CultMath.floor(player.boundingBox.minZ) - 2;
        int maxZ = CultMath.ceil(player.boundingBox.maxZ) + 2;
        int xSize = maxX - minX + 1;
        int zSize = maxZ - minZ + 1;
        int[] states = new int[(maxY - minY + 1) * zSize * xSize];
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    states[index(x, y, z, minX, minY, minZ, xSize, zSize)] =
                            player.compensatedWorld.getBlockStateIdAt(x, y, z);
                }
            }
        }
        return new BlockGrid(minX, minY, minZ, maxX, maxY, maxZ, states);
    }

    /** Prints the grid, one layer per y from the top, each block padded to the longest name. */
    public void appendTo(StringBuilder sb) {
        int maxPosLength = maxPosLength();
        int maxLength = maxLength();
        sb.ensureCapacity(sb.length() + textLength(maxPosLength, maxLength));

        int xSize = maxX - minX + 1;
        int zSize = maxZ - minZ + 1;
        int last = 0;
        String lastName = null;
        for (int y = maxY; y >= minY; y--) {
            sb.append("y: ");
            sb.append(y);
            sb.append('\n');

            appendPadded(sb, "x: ", maxPosLength);
            for (int x = minX; x <= maxX; x++) {
                int start = sb.length();
                sb.append(x);
                pad(sb, sb.length() - start, maxLength);
            }
            sb.append('\n');

            for (int z = minZ; z <= maxZ; z++) {
                int start = sb.length();
                sb.append("z: ").append(z).append(' ');
                pad(sb, sb.length() - start, maxPosLength);
                for (int x = minX; x <= maxX; x++) {
                    int state = states[index(x, y, z, minX, minY, minZ, xSize, zSize)];
                    if (state != last || lastName == null) {
                        last = state;
                        lastName = name(state);
                    }
                    appendPadded(sb, lastName, maxLength);
                }
                sb.append('\n');
            }

            sb.append("\n\n\n");
        }
    }

    /** The number of chars {@link #appendTo} appends. */
    public int textLength() {
        return textLength(maxPosLength(), maxLength());
    }

    private int textLength(int maxPosLength, int maxLength) {
        int layer = Math.max(3, maxPosLength) + 1 + 3;
        for (int x = minX; x <= maxX; x++) layer += Math.max(length(x), maxLength);
        int row = 1;
        for (int x = minX; x <= maxX; x++) row += Math.max(0, maxLength);
        for (int z = minZ; z <= maxZ; z++) layer += Math.max(4 + length(z), maxPosLength) + row;
        int length = 0;
        for (int y = maxY; y >= minY; y--) length += 3 + length(y) + 1 + layer;
        return length;
    }

    /** The label column width: "x: " or "z: [num] ". */
    private int maxPosLength() {
        int maxPosLength = Math.max(coordinateWidth(minX), coordinateWidth(maxX));
        maxPosLength = Math.max(maxPosLength, Math.max(coordinateWidth(minZ), coordinateWidth(maxZ)));
        return maxPosLength + 4;
    }

    /** The block column width: the longest name and a space. */
    private int maxLength() {
        int maxLength = 0;
        int last = 0;
        for (int i = 0; i < states.length; i++) {
            int state = states[i];
            if (state == last && maxLength > 0) continue;
            last = state;
            maxLength = Math.max(name(state).length(), maxLength);
        }
        return maxLength + 1;
    }

    private static String name(int state) {
        String[] names = NAMES;
        if (state < 0 || state >= names.length) return printedName(state);
        String name = names[state];
        if (name == null) names[state] = name = printedName(state);
        return name;
    }

    private static String printedName(int state) {
        return DataTables.defaults().registry().debugString(state).replace("minecraft:", "");
    }

    private static int coordinateWidth(int coordinate) {
        return (int) Math.ceil(Math.log10(Math.abs(coordinate)));
    }

    /** The number of chars {@link StringBuilder#append(int)} appends. */
    private static int length(int value) {
        long remaining = value;
        int length = 1;
        if (remaining < 0) {
            remaining = -remaining;
            length++;
        }
        while (remaining >= 10) {
            remaining /= 10;
            length++;
        }
        return length;
    }

    private static void appendPadded(StringBuilder sb, String value, int width) {
        sb.append(value);
        pad(sb, value.length(), width);
    }

    private static void pad(StringBuilder sb, int length, int width) {
        if (width > length) sb.repeat(' ', width - length);
    }

    private static int index(int x, int y, int z, int minX, int minY, int minZ, int xSize, int zSize) {
        return ((y - minY) * zSize + z - minZ) * xSize + x - minX;
    }
}
