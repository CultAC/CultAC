package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.math.Vec3;

/**
 * Entity#pistonDeltas and Entity#pistonDeltasGameTime: the X, Y and Z piston movement the entity has taken in that
 * game tick, which Entity#limitPistonMovement holds to 0.51 each.
 */
public record ClientPistonDeltas(double x, double y, double z, long gameTime) {
    public static final ClientPistonDeltas NONE = new ClientPistonDeltas(0, 0, 0, Long.MIN_VALUE);
    private static final double LIMIT = 0.51D;

    /** Entity#limitPistonMovement's result and the deltas it leaves. */
    public record Limited(Vec3 movement, ClientPistonDeltas deltas) {}

    /** Entity#limitPistonMovement of {@code vec} at {@code currentGameTime}. */
    public Limited limit(Vec3 vec, long currentGameTime) {
        if (vec.lengthSqr() <= 1.0E-7) return new Limited(vec, this);
        ClientPistonDeltas deltas = currentGameTime == gameTime ? this : new ClientPistonDeltas(0, 0, 0, currentGameTime);
        if (vec.x != 0.0) {
            double min = clamp(vec.x + deltas.x);
            double amount = min - deltas.x;
            deltas = new ClientPistonDeltas(min, deltas.y, deltas.z, currentGameTime);
            return new Limited(Math.abs(amount) <= 1.0E-5F ? Vec3.ZERO : new Vec3(amount, 0.0, 0.0), deltas);
        } else if (vec.y != 0.0) {
            double min = clamp(vec.y + deltas.y);
            double amount = min - deltas.y;
            deltas = new ClientPistonDeltas(deltas.x, min, deltas.z, currentGameTime);
            return new Limited(Math.abs(amount) <= 1.0E-5F ? Vec3.ZERO : new Vec3(0.0, amount, 0.0), deltas);
        } else if (vec.z != 0.0) {
            double min = clamp(vec.z + deltas.z);
            double amount = min - deltas.z;
            deltas = new ClientPistonDeltas(deltas.x, deltas.y, min, currentGameTime);
            return new Limited(Math.abs(amount) <= 1.0E-5F ? Vec3.ZERO : new Vec3(0.0, 0.0, amount), deltas);
        }
        return new Limited(Vec3.ZERO, deltas);
    }

    // Mth#clamp
    private static double clamp(double value) {
        return value < -LIMIT ? -LIMIT : Math.min(value, LIMIT);
    }
}
