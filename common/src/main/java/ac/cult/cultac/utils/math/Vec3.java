package ac.cult.cultac.utils.math;

import ac.cult.cultac.protocol.value.Direction;

/**
 * Immutable movement vector. Basic arithmetic is adapted from PacketEvents' GPLv3
 * Vector3d; client-specific rounding is verified against the pinned 26.3 Vec3.
 * Kept extensible for the existing prediction-vector provenance hierarchy.
 */
public class Vec3 {
    public static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);
    public final double x, y, z;

    public Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vec3 add(Vec3 other) {
        return add(other.x, other.y, other.z);
    }

    public Vec3 add(double dx, double dy, double dz) {
        return new Vec3(x + dx, y + dy, z + dz);
    }

    public Vec3 subtract(Vec3 other) {
        return subtract(other.x, other.y, other.z);
    }

    public Vec3 subtract(double dx, double dy, double dz) {
        return add(-dx, -dy, -dz);
    }

    public Vec3 scale(double factor) {
        return multiply(factor, factor, factor);
    }

    public Vec3 multiply(Vec3 other) {
        return multiply(other.x, other.y, other.z);
    }

    public Vec3 multiply(double sx, double sy, double sz) {
        return new Vec3(x * sx, y * sy, z * sz);
    }

    public double lengthSqr() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSqr());
    }

    public double horizontalDistanceSqr() {
        return x * x + z * z;
    }

    public Vec3 normalize() {
        double length = length();
        return length < 1.0E-5F ? ZERO : new Vec3(x / length, y / length, z / length);
    }

    public double distanceToSqr(Vec3 other) {
        return distanceToSqr(other.x, other.y, other.z);
    }

    public double distanceToSqr(double px, double py, double pz) {
        double dx = px - x, dy = py - y, dz = pz - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distanceTo(Vec3 other) {
        return Math.sqrt(distanceToSqr(other));
    }

    public double dot(Vec3 other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public Vec3 lerp(Vec3 other, double amount) {
        return new Vec3(
                CultMath.lerp(amount, x, other.x),
                CultMath.lerp(amount, y, other.y),
                CultMath.lerp(amount, z, other.z));
    }

    public Vec3 xRot(float radians) {
        float cos = CultMath.cos(radians), sin = CultMath.sin(radians);
        return new Vec3(x, y * cos + z * sin, z * cos - y * sin);
    }

    public Vec3 yRot(float radians) {
        float cos = CultMath.cos(radians), sin = CultMath.sin(radians);
        return new Vec3(x * cos + z * sin, y, z * cos - x * sin);
    }

    public Vec3 with(Direction.Axis axis, double value) {
        return new Vec3(
                axis == Direction.Axis.X ? value : x,
                axis == Direction.Axis.Y ? value : y,
                axis == Direction.Axis.Z ? value : z);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Vec3 vector
                && Double.compare(x, vector.x) == 0
                && Double.compare(y, vector.y) == 0
                && Double.compare(z, vector.z) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Double.hashCode(x) + Double.hashCode(y)) + Double.hashCode(z);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
