package ac.cult.blocksim.engine;

public record Vec3(double x, double y, double z) {
    public Vec3 subtract(Vec3 other) { return new Vec3(x - other.x, y - other.y, z - other.z); }
    public Vec3 scale(double scale) { return new Vec3(x * scale, y * scale, z * scale); }
    public Vec3 addScaled(Vec3 delta, double scale) { return new Vec3(x + scale * delta.x, y + scale * delta.y, z + scale * delta.z); }
    public double lengthSquared() { return x * x + y * y + z * z; }
    public double distanceSquared(Vec3 other) { return subtract(other).lengthSquared(); }
    public Vec3 yRot(float radians) {
        float cos = VanillaMath.cos(radians), sin = VanillaMath.sin(radians);
        return new Vec3(x * cos + z * sin, y, z * cos - x * sin);
    }
}
