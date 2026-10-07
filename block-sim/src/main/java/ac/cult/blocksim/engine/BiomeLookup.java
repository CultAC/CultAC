package ac.cult.blocksim.engine;

/** Client biome zoom: select one received quart cell from the eight jittered corners. */
public final class BiomeLookup {
    private BiomeLookup() {}

    // Independently expressed from the pinned 26.3 BiomeManager#getBiome and its LCG.
    // The packet already supplies the obfuscated zoom seed; do not hash it again.
    public static BlockPos quartAt(long zoomSeed, int x, int y, int z) {
        int shiftedX = x - 2, shiftedY = y - 2, shiftedZ = z - 2;
        int baseX = shiftedX >> 2, baseY = shiftedY >> 2, baseZ = shiftedZ >> 2;
        double fractionX = (shiftedX & 3) * .25, fractionY = (shiftedY & 3) * .25, fractionZ = (shiftedZ & 3) * .25;
        double nearest = Double.POSITIVE_INFINITY;
        int selected = 0;
        for (int corner = 0; corner < 8; corner++) {
            int ox = corner >> 2, oy = (corner >> 1) & 1, oz = corner & 1;
            long hash = zoomSeed;
            for (int round = 0; round < 2; round++) {
                hash = mix(hash, baseX + ox);
                hash = mix(hash, baseY + oy);
                hash = mix(hash, baseZ + oz);
            }
            double dx = fractionX - ox + jitter(hash);
            hash = mix(hash, zoomSeed);
            double dy = fractionY - oy + jitter(hash);
            hash = mix(hash, zoomSeed);
            double dz = fractionZ - oz + jitter(hash);
            double distance = dz * dz + dy * dy + dx * dx;
            if (distance < nearest) { nearest = distance; selected = corner; }
        }
        return new BlockPos(baseX + (selected >> 2), baseY + ((selected >> 1) & 1), baseZ + (selected & 1));
    }

    private static long mix(long value, long salt) {
        return value * (value * 6364136223846793005L + 1442695040888963407L) + salt;
    }

    private static double jitter(long value) { return (((value >> 24) & 1023L) / 1024.0 - .5) * .9; }
}
