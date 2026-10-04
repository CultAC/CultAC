package ac.cult.placement.api;

import java.util.List;
import java.util.Map;

/** Named client tag membership used by block geometry and item interactions. */
public record GeometryTags(
        Map<String, List<String>> blocks,
        Map<String, List<String>> items,
        Map<String, List<String>> fluids,
        Map<String, List<String>> entities) {
    public GeometryTags {
        blocks = copy(blocks);
        items = copy(items);
        fluids = copy(fluids);
        entities = copy(entities);
    }

    /** Existing geometry-only callers read the same three registries. */
    public GeometryTags(
            Map<String, List<String>> blocks, Map<String, List<String>> items, Map<String, List<String>> fluids) {
        this(blocks, items, fluids, Map.of());
    }

    private static Map<String, List<String>> copy(Map<String, List<String>> source) {
        var copy = new java.util.LinkedHashMap<String, List<String>>();
        source.forEach((key, members) -> copy.put(key, List.copyOf(members)));
        return java.util.Collections.unmodifiableMap(copy);
    }
}
