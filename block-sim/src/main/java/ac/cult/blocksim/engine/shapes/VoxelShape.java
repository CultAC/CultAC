package ac.cult.blocksim.engine.shapes;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Vec3;
import ac.cult.blocksim.interaction.BlockHit;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Immutable discrete occupancy and exact coordinate lists; movement collisions stay outside this module. */
public final class VoxelShape {
    final double[][] coords;
    final boolean[] cubeCoords;
    private final boolean cubeShape;
    private final BitSet cells;
    private final int xSize, ySize, zSize;

    VoxelShape(double[][] coords, boolean[] cubeCoords, boolean cubeShape, BitSet cells) {
        this.coords = coords; this.cubeCoords = cubeCoords; this.cubeShape = cubeShape; this.cells = cells;
        xSize = coords[0].length - 1; ySize = coords[1].length - 1; zSize = coords[2].length - 1;
    }
    public boolean isEmpty() { return cells.isEmpty(); }
    public double[] coordinates(int axis) { return coords[axis].clone(); }
    int size(int axis) { return coords[axis].length - 1; }
    boolean full(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < xSize && y < ySize && z < zSize && cells.get(index(x, y, z));
    }
    private int index(int x, int y, int z) { return (x * ySize + y) * zSize + z; }

    public VoxelShape move(double x, double y, double z) {
        if (isEmpty()) return Shapes.empty();
        var moved = new double[3][]; var offset = new double[]{x, y, z};
        for (int axis = 0; axis < 3; axis++) {
            moved[axis] = coords[axis].clone();
            for (int i = 0; i < moved[axis].length; i++) moved[axis][i] += offset[axis];
        }
        return new VoxelShape(moved, new boolean[3], false, cells);
    }

    public VoxelShape optimize() {
        VoxelShape result = Shapes.empty();
        for (Box box : boxes()) result = Shapes.joinUnoptimized(result, Shapes.create(box), BooleanOp.OR);
        return result;
    }

    public VoxelShape face(Direction direction) {
        if (isEmpty() || this == Shapes.block()) return this;
        return calculateFace(direction);
    }

    private VoxelShape calculateFace(Direction direction) {
        int axis = direction.y() != 0 ? 1 : direction.x() != 0 ? 0 : 2;
        if (cubeLike(axis)) return this;
        boolean positive = direction.x() + direction.y() + direction.z() > 0;
        int slice = findIndex(axis, positive ? 0.9999999 : Shapes.EPSILON);
        var dimensions = new int[]{xSize, ySize, zSize}; dimensions[axis] = 1;
        var sliceCoords = coords.clone(); sliceCoords[axis] = new double[]{0.0, 1.0};
        var flags = cubeCoords.clone(); flags[axis] = true;
        var occupancy = new BitSet();
        for (int x = 0; x < dimensions[0]; x++) for (int y = 0; y < dimensions[1]; y++) for (int z = 0; z < dimensions[2]; z++) {
            if (full(axis == 0 ? slice : x, axis == 1 ? slice : y, axis == 2 ? slice : z)) {
                occupancy.set((x * dimensions[1] + y) * dimensions[2] + z);
            }
        }
        var result = new VoxelShape(sliceCoords, flags, false, occupancy);
        if (result.isEmpty()) return Shapes.empty();
        return result.cubeLike(0) && result.cubeLike(1) && result.cubeLike(2) ? Shapes.block() : result;
    }

    private boolean cubeLike(int axis) {
        return coords[axis].length == 2 && Math.abs(coords[axis][0]) <= Shapes.EPSILON && Math.abs(coords[axis][1] - 1.0) <= Shapes.EPSILON;
    }

    private int findIndex(int axis, double coordinate) {
        if (cubeShape) return cubeFindIndex(axis, coordinate);
        int low = 0, high = coords[axis].length;
        while (low < high) { int middle = (low + high) >>> 1; if (coordinate < coords[axis][middle]) high = middle; else low = middle + 1; }
        return low - 1;
    }

    private int cubeFindIndex(int axis, double coordinate) {
        return (int) Math.floor(Math.max(-1.0, Math.min(size(axis), coordinate * size(axis))));
    }

    /** VoxelShape.clip and AABB.clip: inside-cell test, then ordered entry faces. */
    public BlockHit clip(Vec3 from, Vec3 to, BlockPos pos) {
        Vec3 delta = to.subtract(from);
        if (isEmpty() || delta.lengthSquared() < Shapes.EPSILON) return null;
        Vec3 test = from.addScaled(delta, 0.001);
        if (full(findIndex(0, test.x() - pos.x()), findIndex(1, test.y() - pos.y()), findIndex(2, test.z() - pos.z()))) {
            return new BlockHit(pos, Direction.approximateNearest(delta).opposite(), test, true);
        }
        double[] origin = {from.x(), from.y(), from.z()}, movement = {delta.x(), delta.y(), delta.z()};
        Direction[] negative = {Direction.WEST, Direction.DOWN, Direction.NORTH};
        double closest = 1.0;
        Direction face = null;
        for (Box local : boxes()) {
            Box box = local.move(pos.x(), pos.y(), pos.z());
            double[] min = {box.minX(), box.minY(), box.minZ()}, max = {box.maxX(), box.maxY(), box.maxZ()};
            for (int axis = 0; axis < 3; axis++) {
                double da = movement[axis];
                if (da <= Shapes.EPSILON && da >= -Shapes.EPSILON) continue;
                int b = (axis + 1) % 3, c = (axis + 2) % 3;
                double scale = ((da > 0 ? min[axis] : max[axis]) - origin[axis]) / da;
                double pb = origin[b] + scale * movement[b], pc = origin[c] + scale * movement[c];
                if (0.0 < scale && scale < closest && min[b] - Shapes.EPSILON < pb && pb < max[b] + Shapes.EPSILON
                    && min[c] - Shapes.EPSILON < pc && pc < max[c] + Shapes.EPSILON) {
                    closest = scale;
                    face = da > 0 ? negative[axis] : negative[axis].opposite();
                }
            }
        }
        return face == null ? null : new BlockHit(pos, face, from.addScaled(delta, closest), false);
    }

    /** BitSetDiscreteVoxelShape.forAllBoxes: z strip, then x rectangle, then y prism. */
    public List<Box> boxes() {
        var remaining = (BitSet) cells.clone(); var result = new ArrayList<Box>();
        for (int y = 0; y < ySize; y++) for (int x = 0; x < xSize; x++) {
            int startZ = -1;
            for (int z = 0; z <= zSize; z++) {
                if (z < zSize && remaining.get(index(x, y, z))) { if (startZ == -1) startZ = z; }
                else if (startZ != -1) {
                    int endX = x, endY = y; clearStrip(remaining, startZ, z, x, y);
                    while (stripFull(remaining, startZ, z, endX + 1, y)) { clearStrip(remaining, startZ, z, ++endX, y); }
                    while (rectangleFull(remaining, x, endX + 1, startZ, z, endY + 1)) {
                        for (int cx = x; cx <= endX; cx++) clearStrip(remaining, startZ, z, cx, endY + 1);
                        endY++;
                    }
                    result.add(new Box(coords[0][x], coords[1][y], coords[2][startZ], coords[0][endX + 1], coords[1][endY + 1], coords[2][z]));
                    startZ = -1;
                }
            }
        }
        return List.copyOf(result);
    }

    private boolean stripFull(BitSet remaining, int startZ, int endZ, int x, int y) {
        return x < xSize && y < ySize && remaining.nextClearBit(index(x, y, startZ)) >= index(x, y, endZ);
    }
    private boolean rectangleFull(BitSet remaining, int startX, int endX, int startZ, int endZ, int y) {
        for (int x = startX; x < endX; x++) if (!stripFull(remaining, startZ, endZ, x, y)) return false;
        return true;
    }
    private void clearStrip(BitSet remaining, int startZ, int endZ, int x, int y) { remaining.clear(index(x, y, startZ), index(x, y, endZ)); }
}
