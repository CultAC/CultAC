package ac.grim.grimac.bedrock.prediction.geometry;

public record BlockAabb(
    double minX,
    double minY,
    double minZ,
    double maxX,
    double maxY,
    double maxZ
) {
    public BlockAabb {
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("AABB maximums must be greater than or equal to minimums");
        }
    }

}
