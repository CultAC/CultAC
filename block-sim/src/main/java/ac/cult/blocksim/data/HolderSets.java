package ac.cult.blocksim.data;

import com.google.gson.JsonElement;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/** Canonical registry holder sets: a tag, a single key, or an ordered key list. */
public final class HolderSets {
    private HolderSets() { }
    /** Named received tags replace memberships, including an explicitly empty set. */
    public record Overlay(Map<String, Set<String>> tags) {
        public static final Overlay EMPTY = new Overlay(Map.of());
        public Overlay {
            var copy = new HashMap<String, Set<String>>();
            tags.forEach((key, members) -> copy.put(key, Set.copyOf(members)));
            tags = Map.copyOf(copy);
        }
        public static Overlay differingFrom(DataTables defaults, Map<String, Set<String>> received) {
            var changed = new HashMap<String, Set<String>>();
            received.forEach((key, members) -> {
                if (!members.equals(defaults.tags().get(key))) changed.put(key, members);
            });
            return changed.isEmpty() ? EMPTY : new Overlay(changed);
        }
        public DataTables apply(DataTables defaults) {
            if (tags.isEmpty()) return defaults;
            var merged = new HashMap<>(defaults.tags());
            merged.putAll(tags);
            return new DataTables(defaults.version(), defaults.vanillaSha256(), defaults.reportSha256(),
                defaults.enabledFeatures(), defaults.registry(), defaults.items(), merged);
        }
    }
    public static String identifier(String key) { return key.contains(":") ? key : "minecraft:" + key; }
    public static boolean contains(DataTables data, String registry, JsonElement set, String key) {
        if (set.isJsonArray()) {
            for (var member : set.getAsJsonArray()) if (identifier(member.getAsString()).equals(key)) return true;
            return false;
        }
        String name = set.getAsString();
        return name.startsWith("#") ? data.tags().getOrDefault(registry + ":" + identifier(name.substring(1)), Set.of()).contains(key)
            : identifier(name).equals(key);
    }
}
