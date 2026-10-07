package ac.cult.cultac.protocol.value;

import java.util.Iterator;
import java.util.List;

/** Six block faces in vanilla's 3D data order. */
public enum Direction {
    DOWN(0, -1, 0),
    UP(0, 1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    EAST(1, 0, 0);
    private static final Direction[] FACES = values();
    private final int x, y, z;

    Direction(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int getModX() {
        return x;
    }

    public int getModY() {
        return y;
    }

    public int getModZ() {
        return z;
    }

    public int getStepX() {
        return x;
    }

    public int getStepY() {
        return y;
    }

    public int getStepZ() {
        return z;
    }

    public int get3DDataValue() {
        return ordinal();
    }

    /** The legacy helper reflects negative IDs; the wire codec wraps them. */
    public static Direction from3DDataValue(int id) {
        return FACES[Math.abs(id % FACES.length)];
    }

    public static Direction fromWireId(int id) {
        return FACES[Math.floorMod(id, FACES.length)];
    }

    public Direction getOpposite() {
        return getOppositeFace();
    }

    public Axis getAxis() {
        return x != 0 ? Axis.X : y != 0 ? Axis.Y : Axis.Z;
    }

    public AxisDirection getAxisDirection() {
        return x + y + z > 0 ? AxisDirection.POSITIVE : AxisDirection.NEGATIVE;
    }

    public Direction getOppositeFace() {
        return switch (this) {
            case DOWN -> UP;
            case UP -> DOWN;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case WEST -> EAST;
            case EAST -> WEST;
        };
    }

    public enum Axis {
        X,
        Y,
        Z;

        public boolean isVertical() {
            return this == Y;
        }

        public boolean isHorizontal() {
            return this != Y;
        }
    }

    public enum AxisDirection {
        POSITIVE(1),
        NEGATIVE(-1);
        private final int step;

        AxisDirection(int step) {
            this.step = step;
        }

        public int getStep() {
            return step;
        }
    }

    /** Client flow and neighbor traversal order, separate from wire face order. */
    public enum Plane implements Iterable<Direction> {
        HORIZONTAL(List.of(NORTH, EAST, SOUTH, WEST)),
        VERTICAL(List.of(UP, DOWN));
        private final List<Direction> faces;

        Plane(List<Direction> faces) {
            this.faces = faces;
        }

        @Override
        public Iterator<Direction> iterator() {
            return faces.iterator();
        }
    }
}
