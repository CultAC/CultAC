package ac.cult.cultac.bedrock.prediction.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ImmutableJsonValue {
    private ImmutableJsonValue() {
    }

    public static Map<String, Object> copyObjectMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return copyMap(values);
    }

    private static Object copyValue(Object value) {
        if (value == null
            || value instanceof String
            || value instanceof Number
            || value instanceof Boolean
            || value instanceof Character) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return copyMap(map);
        }
        if (value instanceof Collection<?> collection) {
            return copyCollection(collection);
        }
        throw new IllegalArgumentException(
            "JSON metadata values must be strings, numbers, booleans, null, lists, or maps: "
                + value.getClass().getName()
        );
    }

    private static Map<String, Object> copyMap(Map<?, ?> values) {
        if (values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            Object key = entry.getKey();
            if (!(key instanceof String stringKey)) {
                throw new IllegalArgumentException("JSON metadata map keys must be strings: " + key);
            }
            copy.put(stringKey, copyValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<Object> copyCollection(Collection<?> values) {
        if (values.isEmpty()) {
            return List.of();
        }
        List<Object> copy = new ArrayList<>(values.size());
        for (Object value : values) {
            copy.add(copyValue(value));
        }
        return Collections.unmodifiableList(copy);
    }
}
