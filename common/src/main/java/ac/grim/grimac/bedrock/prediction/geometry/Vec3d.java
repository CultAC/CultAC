package ac.grim.grimac.bedrock.prediction.geometry;

public record Vec3d(double x, double y, double z) {
    public static final Vec3d ZERO = new Vec3d(0.0D, 0.0D, 0.0D);

    public Vec3d {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
    }

    public Vec3d add(Vec3d other) {
        return new Vec3d(x + other.x, y + other.y, z + other.z);
    }

    public Vec3d subtract(Vec3d other) {
        return new Vec3d(x - other.x, y - other.y, z - other.z);
    }

    public Vec3d scale(double factor) {
        return new Vec3d(x * factor, y * factor, z * factor);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double maxAbsComponent() {
        return Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }
}
