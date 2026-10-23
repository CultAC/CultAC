package ac.cult.cultac.utils.data.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface JsonSerializable {
    @NotNull
    JsonElement toJson();

    static @NotNull JsonArray serializeArray(@Nullable JsonSerializable @NotNull [] serializableArray) {
        JsonArray array = new JsonArray();

        for (JsonSerializable serializable : serializableArray) {
            array.add(serializable == null ? JsonNull.INSTANCE : serializable.toJson());
        }

        return array;
    }
}
