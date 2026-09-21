package ac.cult.cultac.bedrock.bridge;

import com.google.common.hash.HashCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.geysermc.geyser.item.hashing.DataComponentHashers;
import org.geysermc.geyser.item.hashing.MinecraftHashEncoder;
import org.geysermc.geyser.session.cache.registry.JavaRegistryProvider;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponent;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;

/** Uses stock Geyser's component schemas, retaining their values instead of hashing them. */
final class GeyserComponentValues extends MinecraftHashEncoder {
    private final List<Object> values = new ArrayList<>();

    GeyserComponentValues(JavaRegistryProvider registries) {
        super(registries);
    }

    static <V, T extends DataComponentType<V>> Object serialize(JavaRegistryProvider registries, DataComponent<V, T> component) {
        var encoder = new GeyserComponentValues(registries);
        return encoder.value(DataComponentHashers.hasher(component.getType()).hash(component.getValue(), encoder));
    }

    private HashCode retain(Object value) {
        values.add(value);
        // Tokens are unique within this encoding; real hashes could lose values through collisions.
        return HashCode.fromInt(values.size() - 1);
    }

    Object value(HashCode token) { return values.get(token.asInt()); }

    @Override public HashCode empty() { return retain(null); }
    @Override public HashCode emptyMap() { return retain(Map.of()); }
    @Override public HashCode number(Number value) { return retain(value); }
    @Override public HashCode string(String value) { return retain(value); }
    @Override public HashCode bool(boolean value) { return retain(value); }
    @Override public HashCode byteArray(byte[] value) { return retain(value.clone()); }
    @Override public HashCode intArray(int[] value) { return retain(value.clone()); }
    @Override public HashCode longArray(long[] value) { return retain(value.clone()); }

    @Override public HashCode list(List<HashCode> list) {
        return retain(list.stream().map(this::value).toList());
    }

    @Override public HashCode map(Map<HashCode, HashCode> map) {
        var result = new LinkedHashMap<String, Object>();
        map.forEach((key, value) -> result.put((String) value(key), value(value)));
        return retain(result);
    }
}
