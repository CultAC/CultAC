package ac.cult.blocksim.engine.shapes;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Vec3;
import ac.cult.blocksim.interaction.BlockHit;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Immutable discrete occupancy and exact coordinate lists for geometry and axis clipping. */
public final class VoxelShape {
    final double[][] coords;
    private final int cubeCoordinateAxes;
    private final boolean cubeShape;
    private final BitSet cells;
    private final int xSize, ySize, zSize;

    VoxelShape(double[][] coords, int cubeCoordinateAxes, boolean cubeShape, BitSet cells) {
        this.coords = coords; this.cubeCoordinateAxes = cubeCoordinateAxes; this.cubeShape = cubeShape; this.cells = cells;
        xSize = coords[0].length - 1; ySize = coords[1].length - 1; zSize = coords[2].length - 1;
    }
    public boolean isEmpty() { return cells.isEmpty(); }
    public double[] coordinates(int axis) { return coords[axis].clone(); }
    /** Read-only scalar access, including every internal grid face used by stepping. */
    public int coordinateCount(int axis) { return coords[axis].length; }
    public double coordinateAt(int axis, int index) { return coords[axis][index]; }
    boolean cubeCoordinates(int axis) { return (cubeCoordinateAxes & (1 << axis)) != 0; }
    public boolean sameGrid(VoxelShape other) {
        for (int axis = 0; axis < 3; axis++) if (!java.util.Arrays.equals(coords[axis], other.coords[axis])) return false;
        return true;
    }

    /** Used to retain eager box validation before tests of potentially invalid moved geometry. */
    public boolean hasFiniteCoordinates() {
        for (double[] axis : coords) for (double coordinate : axis) if (!Double.isFinite(coordinate)) return false;
        return true;
    }

    /** Vanilla VoxelShape.collideX scans occupied cells past the actor's leading face. */
    public double collide(int axis, Box moving, double distance) {
        if (isEmpty()) return distance;
        if (Math.abs(distance) < Shapes.EPSILON) return 0.0;
        return collide(axis, moving.minX(), moving.minY(), moving.minZ(), moving.maxX(), moving.maxY(), moving.maxZ(), distance);
    }

    public double collide(int axis, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double distance) {
        return collide(axis, minX, minY, minZ, maxX, maxY, maxZ, distance, 0, 0, 0, false);
    }

    /**
     * Moonrise-style shared local grid with one translation evaluated on read.
     * Compare against coordinate + offset, as vanilla OffsetDoubleList does;
     * subtracting the offset from the actor can change epsilon decisions.
     */
    public double collideTranslated(int axis, double minX, double minY, double minZ, double maxX, double maxY,
                                    double maxZ, double distance, double offsetX, double offsetY, double offsetZ) {
        return collide(axis, minX, minY, minZ, maxX, maxY, maxZ, distance, offsetX, offsetY, offsetZ, true);
    }

    private double collide(int axis, double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                           double distance, double offsetX, double offsetY, double offsetZ, boolean translated) {
        Box.validateBounds(minX, minY, minZ, maxX, maxY, maxZ);
        if (isEmpty()) return distance;
        if (Math.abs(distance) < Shapes.EPSILON) return 0.0;
        if (translated && xSize == 1 && ySize == 1 && zSize == 1
                && Double.isFinite(offsetX) && Double.isFinite(offsetY) && Double.isFinite(offsetZ)) {
            return collideSingleCell(axis, minX, minY, minZ, maxX, maxY, maxZ, distance, offsetX, offsetY, offsetZ);
        }
        int b = (axis + 1) % 3, c = (axis + 2) % 3;
        double offsetA = coordinate(axis, offsetX, offsetY, offsetZ);
        double offsetB = coordinate(b, offsetX, offsetY, offsetZ);
        double offsetC = coordinate(c, offsetX, offsetY, offsetZ);
        int fromB = Math.max(0, collisionIndex(b, coordinate(b, minX, minY, minZ) + Shapes.EPSILON, offsetB, translated));
        int toB = Math.min(size(b), collisionIndex(b, coordinate(b, maxX, maxY, maxZ) - Shapes.EPSILON, offsetB, translated) + 1);
        int fromC = Math.max(0, collisionIndex(c, coordinate(c, minX, minY, minZ) + Shapes.EPSILON, offsetC, translated));
        int toC = Math.min(size(c), collisionIndex(c, coordinate(c, maxX, maxY, maxZ) - Shapes.EPSILON, offsetC, translated) + 1);
        if (fromB >= toB || fromC >= toC) return distance;
        double min = coordinate(axis, minX, minY, minZ), max = coordinate(axis, maxX, maxY, maxZ);
        int start = distance > 0 ? collisionIndex(axis, max - Shapes.EPSILON, offsetA, translated) + 1
                : collisionIndex(axis, min + Shapes.EPSILON, offsetA, translated) - 1;
        int step = distance > 0 ? 1 : -1;
        for (int a = start; a >= 0 && a < size(axis); a += step) {
            double face = coords[axis][distance > 0 ? a : a + 1];
            double gap = (translated ? face + offsetA : face) - (distance > 0 ? max : min);
            // Moonrise: later faces cannot clip a movement that stops before this face.
            if (distance > 0 ? gap >= distance : gap <= distance) return distance;
            for (int ib = fromB; ib < toB; ib++) for (int ic = fromC; ic < toC; ic++) {
                boolean occupied = switch (axis) {
                    case 0 -> full(a, ib, ic);
                    case 1 -> full(ic, a, ib);
                    case 2 -> full(ib, ic, a);
                    default -> throw new IllegalArgumentException("Invalid collision axis");
                };
                if (occupied) {
                    if (distance > 0 && gap >= -Shapes.EPSILON) return Math.min(distance, gap);
                    if (distance < 0 && gap <= Shapes.EPSILON) return Math.max(distance, gap);
                    return distance;
                }
            }
        }
        return distance;
    }

    /** Paper's single-AABB shortcut, using vanilla's exact grid-index comparisons at epsilon ties. */
    private double collideSingleCell(int axis, double minX, double minY, double minZ, double maxX, double maxY,
                                     double maxZ, double distance, double offsetX, double offsetY, double offsetZ) {
        int b = (axis + 1) % 3, c = (axis + 2) % 3;
        double offsetB = coordinate(b, offsetX, offsetY, offsetZ), offsetC = coordinate(c, offsetX, offsetY, offsetZ);
        if (coordinate(b, minX, minY, minZ) + Shapes.EPSILON >= coords[b][1] + offsetB
                || coordinate(b, maxX, maxY, maxZ) - Shapes.EPSILON < coords[b][0] + offsetB
                || coordinate(c, minX, minY, minZ) + Shapes.EPSILON >= coords[c][1] + offsetC
                || coordinate(c, maxX, maxY, maxZ) - Shapes.EPSILON < coords[c][0] + offsetC) return distance;
        double offsetA = coordinate(axis, offsetX, offsetY, offsetZ);
        double min = coordinate(axis, minX, minY, minZ), max = coordinate(axis, maxX, maxY, maxZ);
        if (distance > 0) {
            double face = coords[axis][0] + offsetA;
            if (!(max - Shapes.EPSILON < face)) return distance;
            double gap = face - max;
            return gap >= -Shapes.EPSILON ? Math.min(distance, gap) : distance;
        } else if (distance < 0) {
            double face = coords[axis][1] + offsetA;
            if (!(min + Shapes.EPSILON >= face)) return distance;
            double gap = face - min;
            return gap <= Shapes.EPSILON ? Math.max(distance, gap) : distance;
        }
        return distance;
    }

    private int collisionIndex(int axis, double value, double offset, boolean translated) {
        if (!translated) return findIndex(axis, value);
        // move() produces an ArrayVoxelShape even for a cube grid.
        int low = 0, high = coords[axis].length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (value < coords[axis][middle] + offset) high = middle;
            else low = middle + 1;
        }
        return low - 1;
    }
    private static double coordinate(int axis, double x, double y, double z) {
        return switch (axis) { case 0 -> x; case 1 -> y; case 2 -> z; default -> throw new ArrayIndexOutOfBoundsException(axis); };
    }
    int size(int axis) { return coords[axis].length - 1; }
    boolean full(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < xSize && y < ySize && z < zSize && cells.get(index(x, y, z));
    }
    private int index(int x, int y, int z) { return (x * ySize + y) * zSize + z; }

    boolean anyFullZ(int x, int y, int minZ, int maxZ) {
        int first = cells.nextSetBit(index(x, y, minZ));
        return first >= 0 && first < index(x, y, maxZ);
    }

    /** Direct occupancy intersection, with the original Boolean join for ambiguous inputs. */
    public boolean intersectsAabb(VoxelShape query, double offsetX, double offsetY, double offsetZ, boolean translated) {
        return AabbVoxelIntersection.intersects(this, query, offsetX, offsetY, offsetZ, translated);
    }

    /** {@link #intersectsAabb(VoxelShape, double, double, double, boolean)} with {@code Shapes.create} of the bounds. */
    public boolean intersectsAabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                  double offsetX, double offsetY, double offsetZ, boolean translated) {
        return AabbVoxelIntersection.intersects(this, minX, minY, minZ, maxX, maxY, maxZ, offsetX, offsetY, offsetZ, translated);
    }

    public VoxelShape move(double x, double y, double z) {
        if (isEmpty()) return Shapes.empty();
        var moved = new double[3][];
        for (int axis = 0; axis < 3; axis++) {
            moved[axis] = coords[axis].clone();
            double offset = coordinate(axis, x, y, z);
            for (int i = 0; i < moved[axis].length; i++) moved[axis][i] += offset;
        }
        return new VoxelShape(moved, 0, false, cells);
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
        int flags = cubeCoordinateAxes | (1 << axis);
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
        var result = new ArrayList<Box>();
        forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> result.add(new Box(minX, minY, minZ, maxX, maxY, maxZ)));
        return List.copyOf(result);
    }

    @FunctionalInterface
    public interface BoxConsumer {
        void accept(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
    }

    /** Same ordered decomposition, with call-local scratch and no materialized box records. */
    public void forEachBox(BoxConsumer consumer) {
        int first = cells.nextSetBit(0);
        if (first < 0) return;
        if (cells.nextSetBit(first + 1) < 0) {
            int z = first % zSize, xy = first / zSize, y = xy % ySize, x = xy / ySize;
            emit(consumer, x, y, z, x + 1, y + 1, z + 1);
            return;
        }
        var remaining = (BitSet) cells.clone();
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
                    emit(consumer, x, y, startZ, endX + 1, endY + 1, z);
                    startZ = -1;
                }
            }
        }
    }

    private void emit(BoxConsumer consumer, int x1, int y1, int z1, int x2, int y2, int z2) {
        double minX = coords[0][x1], minY = coords[1][y1], minZ = coords[2][z1];
        double maxX = coords[0][x2], maxY = coords[1][y2], maxZ = coords[2][z2];
        Box.validateBounds(minX, minY, minZ, maxX, maxY, maxZ);
        consumer.accept(minX, minY, minZ, maxX, maxY, maxZ);
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
