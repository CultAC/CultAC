package ac.cult.blocksim.data;

import ac.cult.blocksim.data.nbt.NbtValue;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** Source-defined numeric record details omitted by the client's persistent NBT encoding. */
final class ComponentNumbers {
    private ComponentNumbers() {}

    static JsonElement restore(JsonElement value, NbtValue signs) {
        if (signs instanceof NbtValue.Numeric kind) {
            return kind.value().intValue() == 5 ? new JsonPrimitive(-0.0F) : new JsonPrimitive(-0.0D);
        }
        if (signs instanceof NbtValue.Compound fields) {
            fields.values().forEach((key, child) -> {
                if (value.isJsonArray()) {
                    int index = Integer.parseInt(key);
                    value.getAsJsonArray().set(index, restore(value.getAsJsonArray().get(index), child));
                } else value.getAsJsonObject().add(key, restore(value.getAsJsonObject().get(key), child));
            });
        }
        return value;
    }
}
