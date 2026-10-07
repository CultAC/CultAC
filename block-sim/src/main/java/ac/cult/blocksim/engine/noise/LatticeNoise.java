package ac.cult.blocksim.engine.noise;

import java.util.Random;

/** Seeded Perlin lattice with the client's float interpolation and periodic coordinates. */
final class LatticeNoise {
    private static final double PERIOD = 33554432.0;
    private static final double HALF_PERIOD = Math.nextDown(PERIOD / 2);
    private final int[] permutation = new int[256];
    private final double offsetX, offsetY, offsetZ;

    LatticeNoise(long seed) {
        // The legacy client generator uses the same 48-bit algorithm as java.util.Random.
        var random = new Random(seed);
        offsetX = random.nextDouble() * 256;
        offsetY = random.nextDouble() * 256;
        offsetZ = random.nextDouble() * 256;
        for (int i = 0; i < permutation.length; i++) permutation[i] = i;
        for (int i = 0; i < permutation.length; i++) {
            int next = i + random.nextInt(permutation.length - i);
            int old = permutation[i]; permutation[i] = permutation[next]; permutation[next] = old;
        }
    }

    float sample(double x, double y, double z) {
        x = wrap(x) + offsetX; y = wrap(y) + offsetY; z = wrap(z) + offsetZ;
        int ix = floor(x), iy = floor(y), iz = floor(z);
        float rx = (float) (x - ix), ry = (float) (y - iy), rz = (float) (z - iz);
        var corners = new float[8];
        for (int corner = 0; corner < corners.length; corner++) {
            int cx = corner & 1, cy = corner >> 1 & 1, cz = corner >> 2;
            int gradient = permute(permute(permute(ix + cx) + iy + cy) + iz + cz);
            corners[corner] = dot(gradient, rx - cx, ry - cy, rz - cz);
        }
        // Reduce the eight corners along X, then Y, then Z; each operation stays float.
        for (int axis = 0, stride = 1; axis < 3; axis++, stride <<= 1) {
            float alpha = fade(axis == 0 ? rx : axis == 1 ? ry : rz);
            for (int corner = 0; corner < 8; corner += stride * 2)
                corners[corner] += alpha * (corners[corner + stride] - corners[corner]);
        }
        return corners[0];
    }

    private int permute(int value) { return permutation[value & 255]; }
    private static double wrap(double value) {
        return value >= -HALF_PERIOD && value < HALF_PERIOD ? value : value - Math.floor(value / PERIOD + .5) * PERIOD;
    }
    private static int floor(double value) { int result = (int) value; return value < result ? result - 1 : result; }
    private static float fade(float value) { return value * value * value * (value * (value * 6 - 15) + 10); }
    private static float dot(int hash, float x, float y, float z) {
        hash &= 15;
        int firstAxis = hash < 8 ? 0 : 1;
        int secondAxis = hash < 4 ? 1 : hash == 12 || hash == 14 ? 0 : 2;
        int firstSign = (hash & 1) == 0 ? 1 : -1, secondSign = (hash & 2) == 0 ? 1 : -1;
        // Keep the zero terms and X/Y/Z addition order as well as the gradient directions.
        return (firstAxis == 0 ? firstSign : secondAxis == 0 ? secondSign : 0) * x
                + (firstAxis == 1 ? firstSign : secondAxis == 1 ? secondSign : 0) * y
                + (firstAxis == 2 ? firstSign : secondAxis == 2 ? secondSign : 0) * z;
    }
}
