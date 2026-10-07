package ac.cult.blocksim.enchantment;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;

/** A received unfiltered effect, compiled once without loading client classes. */
@FunctionalInterface
public interface ValueEffect {
    float apply(int level, EffectRandom random, float input);

    static ValueEffect read(JsonElement value) {
        var object = value.getAsJsonObject();
        String type = object.get("type").getAsString();
        if (!type.contains(":")) type = "minecraft:" + type;
        return switch (type) {
            case "minecraft:add" -> {
                var amount = LevelValue.read(object.get("value"));
                yield (level, random, input) -> input + amount.calculate(level);
            }
            case "minecraft:multiply" -> {
                var factor = LevelValue.read(object.get("factor"));
                yield (level, random, input) -> input * factor.calculate(level);
            }
            case "minecraft:set" -> {
                var amount = LevelValue.read(object.get("value"));
                yield (level, random, input) -> amount.calculate(level);
            }
            case "minecraft:exponential" -> {
                var base = LevelValue.read(object.get("base"));
                var exponent = LevelValue.read(object.get("exponent"));
                yield (level, random, input) -> (float) (input * Math.pow(base.calculate(level), exponent.calculate(level)));
            }
            case "minecraft:all_of" -> {
                var entries = new ArrayList<ValueEffect>();
                for (var entry : object.getAsJsonArray("effects")) entries.add(read(entry));
                var effects = List.copyOf(entries);
                yield (level, random, input) -> {
                    float result = input;
                    for (var effect : effects) result = effect.apply(level, random, result);
                    return result;
                };
            }
            case "minecraft:remove_binomial" -> {
                var chance = LevelValue.read(object.get("chance"));
                yield (level, random, input) -> remove(input, chance.calculate(level), random);
            }
            default -> throw new IllegalArgumentException("Unknown enchantment value effect " + type);
        };
    }

    private static float remove(float count, float chance, EffectRandom random) {
        int removed = 0;
        if (!(count <= 128.0F) && !(count * chance < 20.0F) && !(count * (1.0F - chance) < 20.0F)) {
            double mean = Math.floor(count * chance);
            double deviation = Math.sqrt(count * chance * (1.0F - chance));
            removed = (int) Math.round(mean + random.nextGaussian() * deviation);
            removed = Math.clamp(removed, 0, (int) count);
        } else {
            for (int trial = 0; trial < count; trial++) if (random.nextFloat() < chance) removed++;
        }
        return count - removed;
    }
}
