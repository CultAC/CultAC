package ac.cult.blocksim.environment;

import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.BlockPos;
import java.util.Random;

/** Biome's precipitation branch; terrain generation and temperature caches are unnecessary here. */
public final class BiomeWeather {
    private BiomeWeather() { }

    /** Reads only the climate fields used by Biome.getPrecipitationAt. */
    public static boolean canRain(NbtValue.Compound biome, BlockPos pos, int seaLevel) {
        if (((NbtValue.Numeric) biome.values().get("has_precipitation")).value().doubleValue() == 0.0) return false;
        float temperature = ((NbtValue.Numeric) biome.values().get("temperature")).value().floatValue();
        var modifier = biome.values().get("temperature_modifier");
        if (modifier instanceof NbtValue.Text text) {
            switch (text.value()) {
                case "none" -> { }
                case "frozen" -> {
                    double large = Noise.frozen(pos.x() * .05, pos.z() * .05) * 7.0F;
                    if (large + Noise.INFO.sample(pos.x() * .2, pos.z() * .2) < .3
                            && Noise.INFO.sample(pos.x() * .09, pos.z() * .09) < .8) temperature = .2F;
                }
                default -> throw new IllegalArgumentException("Unknown temperature modifier " + text.value());
            }
        }
        int snowLevel = seaLevel + 17;
        if (pos.y() > snowLevel) {
            float variation = Noise.TEMPERATURE.sample(pos.x() / 8.0F, pos.z() / 8.0F) * 8.0F;
            temperature -= (variation + pos.y() - snowLevel) * .05F / 40.0F;
        }
        return temperature >= .15F;
    }

    /** Fixed biome seeds. Construction is lazy and shared; no world random state is sampled. */
    private static final class Noise {
        static final Simplex TEMPERATURE = new Simplex(new Random(1234));
        static final Simplex INFO = new Simplex(new Random(2345));
        static final Simplex[] FROZEN;
        static {
            var seed = new Random(3456);
            FROZEN = new Simplex[]{new Simplex(seed), new Simplex(seed), new Simplex(seed)};
        }
        static float frozen(double x, double z) {
            float result = 0;
            result += .14285715F * FROZEN[0].sample(x, z);
            result += .2857143F * FROZEN[1].sample(x * .5, z * .5);
            result += .5714286F * FROZEN[2].sample(x * .25, z * .25);
            return result;
        }
    }

    /** Standard two-dimensional simplex lattice, limited to the biome's offset-free samplers. */
    private static final class Simplex {
        private static final double SKEW = (Math.sqrt(3) - 1) * .5;
        private static final double UNSKEW = (3 - Math.sqrt(3)) / 6;
        private final byte[] permutation = new byte[256];

        Simplex(Random random) {
            // Even discarded offsets consume the three legacy random doubles.
            for (int i = 0; i < 3; i++) random.nextDouble();
            for (int i = 0; i < 256; i++) permutation[i] = (byte) i;
            for (int i = 0; i < 256; i++) {
                int selected = i + random.nextInt(256 - i);
                byte previous = permutation[i];
                permutation[i] = permutation[selected]; permutation[selected] = previous;
            }
        }

        float sample(double x, double z) {
            double skew = (x + z) * SKEW;
            int cellX = (int) Math.floor(x + skew), cellZ = (int) Math.floor(z + skew);
            double unskew = (cellX + cellZ) * UNSKEW;
            double localX = x - (cellX - unskew), localZ = z - (cellZ - unskew);
            int middleX = localX > localZ ? 1 : 0, middleZ = 1 - middleX;
            double first = corner(cellX, cellZ, localX, localZ);
            double middle = corner(cellX + middleX, cellZ + middleZ,
                localX - middleX + UNSKEW, localZ - middleZ + UNSKEW);
            double last = corner(cellX + 1, cellZ + 1,
                localX - 1 + 2 * UNSKEW, localZ - 1 + 2 * UNSKEW);
            return (float) (70 * (first + middle + last));
        }

        private int permute(int value) { return permutation[value & 255] & 255; }
        private double corner(int cellX, int cellZ, double x, double z) {
            double weight = .5 - x * x - z * z;
            if (weight < 0) return 0;
            int gradient = permute(cellX + permute(cellZ)) % 12;
            int firstSign = (gradient & 1) == 0 ? 1 : -1;
            int secondSign = (gradient & 2) == 0 ? 1 : -1;
            double dot = (gradient < 8 ? firstSign : 0) * x
                + (gradient < 4 ? secondSign : gradient >= 8 ? firstSign : 0) * z
                + (gradient >= 4 ? secondSign : 0) * 0.0;
            weight *= weight;
            return weight * weight * dot;
        }
    }
}
