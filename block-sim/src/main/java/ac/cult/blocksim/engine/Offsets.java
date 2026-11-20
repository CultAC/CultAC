package ac.cult.blocksim.engine;

import ac.cult.blocksim.data.BlockDefinition;

/** Position jitter uses vanilla's integer overflow and float intermediates verbatim. */
public final class Offsets {
    private Offsets() { }
    public record Offset(double x, double y, double z) { }

    public static long seed(int x, int y, int z) {
        long seed = x * 3129871 ^ z * 116129781L ^ y;
        seed = seed * seed * 42317861L + seed * 11L;
        return seed >> 16;
    }

    public static Offset offset(BlockDefinition block, BlockPos pos) {
        String type = block.bindings().get("offsetType");
        if ("NONE".equals(type)) return new Offset(0, 0, 0);
        if (!"XZ".equals(type) && !"XYZ".equals(type)) throw new IllegalArgumentException("Unbound offset type for " + block.key());
        long seed = seed(pos.x(), 0, pos.z());
        float maxHorizontal = Float.parseFloat(block.bindings().get("getMaxHorizontalOffset"));
        double x = Math.max(-maxHorizontal, Math.min(maxHorizontal, ((float) (seed & 15L) / 15.0F - 0.5) * 0.5));
        double z = Math.max(-maxHorizontal, Math.min(maxHorizontal, ((float) (seed >> 8 & 15L) / 15.0F - 0.5) * 0.5));
        double y = "XYZ".equals(type) ? ((float) (seed >> 4 & 15L) / 15.0F - 1.0) * Float.parseFloat(block.bindings().get("getMaxVerticalOffset")) : 0;
        return new Offset(x, y, z);
    }
}
