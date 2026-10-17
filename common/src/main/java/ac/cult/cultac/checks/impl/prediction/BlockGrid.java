package ac.cult.cultac.checks.impl.prediction;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.CultMath;

/**
 * The blocks around a player's bounding box, two blocks out, as {@link SuperDebug} prints them. The grid holds model
 * block-state ids, so a grid read on the player's packet thread may be printed on any other.
 */
public final class BlockGrid {
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

        maxPosLength += 4; // To handle "x: [num] "
        maxLength++; // Add a space between blocks

        for (int y = maxY; y >= minY; y--) {
            sb.append("y: ");
            sb.append(y);
            sb.append("\n");

            appendPadded(sb, "x: ", maxPosLength);
            for (int x = minX; x <= maxX; x++) {
                appendPadded(sb, Integer.toString(x), maxLength);
            }
            sb.append("\n");

            for (int z = minZ; z <= maxZ; z++) {
                appendPadded(sb, "z: " + z + " ", maxPosLength);
                for (int x = minX; x <= maxX; x++) {
                    appendPadded(sb, nearbyBlocks[index(x, y, z, minX, minY, minZ, xSize, zSize)], maxLength);
                }
                sb.append("\n");
            }

            sb.append("\n\n\n");
        }
    }

    private static int coordinateWidth(int coordinate) {
        return (int) Math.ceil(Math.log10(Math.abs(coordinate)));
    }

    private static void appendPadded(StringBuilder sb, String value, int width) {
        sb.append(value);
        for (int i = value.length(); i < width; i++) {
            sb.append(' ');
        }
    }

    private static int index(int x, int y, int z, int minX, int minY, int minZ, int xSize, int zSize) {
        return ((y - minY) * zSize + z - minZ) * xSize + x - minX;
    }
}
