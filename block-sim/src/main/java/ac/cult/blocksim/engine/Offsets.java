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
        double x = horizontal(seed, 0, maxHorizontal);
        double z = horizontal(seed, 8, maxHorizontal);
        double y = "XYZ".equals(type) ? vertical(seed, Float.parseFloat(block.bindings().get("getMaxVerticalOffset"))) : 0;
        return new Offset(x, y, z);
    }

    /** One horizontal axis from the four seed bits at shift, clamped to the maximum. */
    public static double horizontal(long seed, int shift, float max) {
        return Math.max(-max, Math.min(max, ((float) (seed >> shift & 15L) / 15.0F - 0.5) * 0.5));
    }

    public static double vertical(long seed, float max) { return ((float) (seed >> 4 & 15L) / 15.0F - 1.0) * max; }
}
