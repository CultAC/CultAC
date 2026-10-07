package ac.cult.blocksim.enchantment;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;

/** Compiled received level expressions; calculations retain the client's float boundaries. */
@FunctionalInterface
public interface LevelValue {
    float calculate(int level);

    static LevelValue read(JsonElement value) {
        if (value.isJsonPrimitive()) {
            float constant = value.getAsFloat();
            return level -> constant;
        }
        var object = value.getAsJsonObject();
        String type = object.get("type").getAsString();
        if (!type.contains(":")) type = "minecraft:" + type;
        return switch (type) {
            case "minecraft:linear" -> {
                float base = object.get("base").getAsFloat();
                float increment = object.get("per_level_above_first").getAsFloat();
                yield level -> base + increment * (level - 1);
            }
            case "minecraft:levels_squared" -> {
                float added = object.get("added").getAsFloat();
                yield level -> level * level + added;
            }
            case "minecraft:fraction" -> {
                var numerator = read(object.get("numerator"));
                var denominator = read(object.get("denominator"));
                yield level -> {
                    float divisor = denominator.calculate(level);
                    return divisor == 0.0F ? 0.0F : numerator.calculate(level) / divisor;
                };
            }
            case "minecraft:exponent" -> {
                var base = read(object.get("base"));
                var power = read(object.get("power"));
                yield level -> (float) Math.pow(base.calculate(level), power.calculate(level));
            }
            case "minecraft:clamped" -> {
                var expression = read(object.get("value"));
                float min = object.get("min").getAsFloat(), max = object.get("max").getAsFloat();
                if (max <= min) throw new IllegalArgumentException("Level expression max must exceed min");
                yield level -> {
                    float result = expression.calculate(level);
                    return result < min ? min : Math.min(result, max);
                };
            }
            case "minecraft:lookup" -> {
                var entries = new ArrayList<Float>();
                for (var entry : object.getAsJsonArray("values")) entries.add(entry.getAsFloat());
                var values = List.copyOf(entries);
                var fallback = read(object.get("fallback"));
                yield level -> level <= values.size() ? values.get(level - 1) : fallback.calculate(level);
            }
            default -> throw new IllegalArgumentException("Unknown enchantment level expression " + type);
        };
    }
}
