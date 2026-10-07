package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.noise.SeededNoise;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.function.ToIntFunction;

/** Fixed-seed noise and dual-noise providers; neither consumes the client's world random source. */
final class NoiseStateProvider {
    private final SeededNoise noise, slowNoise;
    private final float scale, slowScale;
    private final int[] states;
    private final int minimumVariety, maximumVariety;

    NoiseStateProvider(JsonObject value, ToIntFunction<JsonElement> state) {
        long seed = value.get("seed").getAsLong();
        noise = new SeededNoise(seed, value.getAsJsonObject("noise"));
        scale = value.get("scale").getAsFloat();
        states = value.getAsJsonArray("states").asList().stream().mapToInt(state).toArray();
        if (value.has("slow_noise")) {
            slowNoise = new SeededNoise(seed, value.getAsJsonObject("slow_noise"));
            slowScale = value.get("slow_scale").getAsFloat();
            var variety = value.get("variety");
            minimumVariety = variety.isJsonArray() ? variety.getAsJsonArray().get(0).getAsInt() : variety.getAsInt();
            maximumVariety = variety.isJsonArray() ? variety.getAsJsonArray().get(1).getAsInt() : minimumVariety;
        } else { slowNoise = null; slowScale = 0; minimumVariety = maximumVariety = 0; }
    }

    int state(BlockPos pos) {
        float localNoise = noise.sample(pos.x() * (double) scale, pos.y() * (double) scale, pos.z() * (double) scale);
        if (slowNoise == null) return choose(states, localNoise);
        double varietyNoise = slow(pos);
        double fraction = (varietyNoise - -1.0) / (1.0 - -1.0);
        int count = (int) (fraction < 0 ? minimumVariety : fraction > 1 ? maximumVariety + 1
                : minimumVariety + fraction * (maximumVariety + 1 - minimumVariety));
        var localStates = new int[count];
        for (int i = 0; i < count; i++) localStates[i] = choose(states, slow(new BlockPos(pos.x() + i * 54545, pos.y(), pos.z() + i * 34234)));
        return choose(localStates, localNoise);
    }

    private float slow(BlockPos pos) {
        // DualNoiseProvider multiplies integer coordinates by a float, unlike the fast double scale.
        return slowNoise.sample(pos.x() * slowScale, pos.y() * slowScale, pos.z() * slowScale);
    }
    private static int choose(int[] states, float noise) {
        float value = Math.min(Math.max((1.0F + noise) / 2.0F, 0.0F), .9999F);
        return states[(int) (value * states.length)];
    }
}
