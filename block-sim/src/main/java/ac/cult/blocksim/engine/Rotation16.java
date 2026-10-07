package ac.cult.blocksim.engine;


/** RotationSegment's fixed four-bit SegmentedAnglePrecision. */
public final class Rotation16 {
    private Rotation16() { }
    private static int withTurns(float degrees) { return Math.round(degrees * (16 / 360.0F)); }
    public static int fromDegrees(float degrees) { return withTurns(degrees) & 15; }
    public static int fromDirection(Direction direction) {
        return switch (direction) { case SOUTH -> 0; case WEST -> 4; case NORTH -> 8; case EAST -> 12; default -> 0; };
    }
    public static Direction toDirection(int segment) {
        return switch (segment) { case 0 -> Direction.NORTH; case 4 -> Direction.EAST; case 8 -> Direction.SOUTH; case 12 -> Direction.WEST; default -> null; };
    }
}
