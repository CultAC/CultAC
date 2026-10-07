package ac.cult.blocksim.engine;

public record BlockPos(int x, int y, int z) {
    public BlockPos relative(Direction direction) {
        return new BlockPos(x + direction.x(), y + direction.y(), z + direction.z());
    }
    public BlockPos relative(Direction direction, int distance) {
        return new BlockPos(x + direction.x() * distance, y + direction.y() * distance, z + direction.z() * distance);
    }
}
