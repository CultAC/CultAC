package ac.cult.blocksim.engine.noise;

import ac.cult.blocksim.data.nbt.NbtJson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Random;
import java.util.stream.IntStream;

/** Immutable octave sampler for 26.3 noise provider settings; no world RNG is retained. */
public final class SeededNoise {
    private record Layer(LatticeNoise lattice, double frequency, float amplitude) { }
    private final Layer[] layers;

    public SeededNoise(long seed, JsonObject settings) {
        int first = settings.get("base_octave").getAsInt();
        int count = settings.has("octave_count") ? settings.get("octave_count").getAsInt() : 1;
        double base = settings.has("base_amplitude") ? settings.get("base_amplitude").getAsDouble() : 1;
        var normalization = settings.get("normalize");
        boolean legacy = normalization != null && normalization.getAsJsonPrimitive().isString()
                && normalization.getAsString().equals("legacy");
        boolean normalized = normalization == null || legacy || NbtJson.booleanValue(normalization);
        double amplitude = normalized ? base * (Math.pow(.5, -(count - 1)) / (Math.pow(.5, -count) - 1)) : base;
        var modifiers = settings.getAsJsonArray("amplitude_modifiers");
        var amplitudes = new double[count];
        var enabled = new boolean[count];
        double variance = 0;
        int firstEnabled = count, lastEnabled = -1;
        for (int i = 0; i < count; i++, amplitude *= .5) {
            double modifier = modifiers == null || modifiers.isEmpty() ? 1 : modifiers.get(i).getAsDouble();
            if (modifier == 0) continue;
            enabled[i] = true;
            firstEnabled = Math.min(firstEnabled, i); lastEnabled = i;
            amplitudes[i] = amplitude * modifier;
            double deviation = .2702247831245211 * Math.abs(amplitudes[i]);
            variance += deviation * deviation;
        }
        double deviation = Math.sqrt(variance);
        double factor = deviation == 0 ? 0 : IntStream.range(0, count).filter(i -> enabled[i])
                .mapToDouble(i -> Math.abs(amplitudes[i])).sum() * (1.0 / 3) / (deviation * Math.sqrt(2));
        if (legacy && factor != 0)
            factor = base * .5 * (1.0 / 3) / (.1 * (1 + 1.0 / (lastEnabled - firstEnabled + 1)));
        var random = new Random(seed);
        long firstSeed = random.nextLong(), secondSeed = random.nextLong();
        var built = new ArrayList<Layer>(count * 2);
        double frequency = Math.pow(2, first);
        for (int i = 0; i < count; i++, frequency *= 2) {
            if (!enabled[i]) continue;
            int nameHash = ("octave_" + (first + i)).hashCode();
            float weight = (float) (factor * amplitudes[i]);
            built.add(new Layer(new LatticeNoise(firstSeed ^ nameHash), frequency, weight));
            built.add(new Layer(new LatticeNoise(secondSeed ^ nameHash), frequency * 1.0181268882175227, weight));
        }
        layers = built.toArray(Layer[]::new);
    }

    public float sample(double x, double y, double z) {
        float result = 0;
        for (var layer : layers) result += layer.amplitude * layer.lattice.sample(
                x * layer.frequency, y * layer.frequency, z * layer.frequency);
        return result;
    }
}
