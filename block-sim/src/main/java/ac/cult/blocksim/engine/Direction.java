package ac.cult.blocksim.engine;


/** Vanilla wire/ordinal order; shape-update order is deliberately a separate list. */
public enum Direction {
    DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

    private final int x, y, z;
    Direction(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public Direction opposite() { return values()[ordinal() ^ 1]; }
    public boolean horizontal() { return y == 0; }
    public String axisName() { return x != 0 ? "x" : y != 0 ? "y" : "z"; }

    public Direction clockwise() {
        return switch (this) { case NORTH -> EAST; case EAST -> SOUTH; case SOUTH -> WEST; case WEST -> NORTH;
            default -> throw new IllegalStateException("Unable to get Y-rotated facing of " + this); };
    }
    public Direction counterClockwise() {
        return switch (this) { case NORTH -> WEST; case WEST -> SOUTH; case SOUTH -> EAST; case EAST -> NORTH;
            default -> throw new IllegalStateException("Unable to get Y-rotated facing of " + this); };
    }

    public static Direction fromYRot(double yaw) {
        return new Direction[]{SOUTH, WEST, NORTH, EAST}[(int) Math.floor(yaw / 90.0 + 0.5) & 3];
    }

    public static Direction approximateNearest(Vec3 delta) {
        float x = (float) delta.x(), y = (float) delta.y(), z = (float) delta.z();
        Direction result = NORTH;
        float highest = Float.MIN_VALUE;
        for (Direction direction : values()) {
            float dot = x * direction.x + y * direction.y + z * direction.z;
            if (dot > highest) { highest = dot; result = direction; }
        }
        return result;
    }

    public static Direction[] orderedByNearest(float rotationYaw, float rotationPitch) {
        float pitch = rotationPitch * (float) (Math.PI / 180.0);
        float yaw = -rotationYaw * (float) (Math.PI / 180.0);
        float pitchSin = VanillaMath.sin(pitch), pitchCos = VanillaMath.cos(pitch);
        float yawSin = VanillaMath.sin(yaw), yawCos = VanillaMath.cos(yaw);
        boolean xPos = yawSin > 0.0F, yPos = pitchSin < 0.0F, zPos = yawCos > 0.0F;
        float xYaw = xPos ? yawSin : -yawSin;
        float yMag = yPos ? -pitchSin : pitchSin;
        float zYaw = zPos ? yawCos : -yawCos;
        float xMag = xYaw * pitchCos, zMag = zYaw * pitchCos;
        Direction axisX = xPos ? EAST : WEST, axisY = yPos ? UP : DOWN, axisZ = zPos ? SOUTH : NORTH;
        if (xYaw > zYaw) {
            if (yMag > xMag) return makeDirectionArray(axisY, axisX, axisZ);
            return zMag > yMag ? makeDirectionArray(axisX, axisZ, axisY) : makeDirectionArray(axisX, axisY, axisZ);
        } else if (yMag > zMag) {
            return makeDirectionArray(axisY, axisZ, axisX);
        }
        return xMag > yMag ? makeDirectionArray(axisZ, axisX, axisY) : makeDirectionArray(axisZ, axisY, axisX);
    }

    private static Direction[] makeDirectionArray(Direction first, Direction second, Direction third) {
        return new Direction[]{first, second, third, third.opposite(), second.opposite(), first.opposite()};
    }
}
