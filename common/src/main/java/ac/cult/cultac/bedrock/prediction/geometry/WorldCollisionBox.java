package ac.cult.cultac.bedrock.prediction.geometry;

public record WorldCollisionBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public WorldCollisionBox {
        requireFinite(minX, "minX");
        requireFinite(minY, "minY");
        requireFinite(minZ, "minZ");
        requireFinite(maxX, "maxX");
        requireFinite(maxY, "maxY");
        requireFinite(maxZ, "maxZ");
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("collision box maximums must be greater than or equal to minimums");
        }
    }

    /** The unit cube of the block at {@code position}. */
    public static WorldCollisionBox fullBlock(BlockPosition position) {
        return new WorldCollisionBox(
                position.x(),
                position.y(),
                position.z(),
                position.x() + 1.0D,
                position.y() + 1.0D,
                position.z() + 1.0D);
    }

    public WorldCollisionBox move(double x, double y, double z) {
        return new WorldCollisionBox(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
    }

    public boolean intersects(WorldCollisionBox other) {
        return maxX > other.minX
                && minX < other.maxX
                && maxY > other.minY
                && minY < other.maxY
                && maxZ > other.minZ
                && minZ < other.maxZ;
    }

    public boolean overlapsXz(WorldCollisionBox other) {
        return maxX > other.minX && minX < other.maxX && maxZ > other.minZ && minZ < other.maxZ;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
