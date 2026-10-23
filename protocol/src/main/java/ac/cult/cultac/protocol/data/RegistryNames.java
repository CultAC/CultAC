package ac.cult.cultac.protocol.data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable name/ID snapshot. No native values or live registries cross the boundary. */
public final class RegistryNames {
    private final Map<String, IdTable> registries;

    public RegistryNames(Map<String, List<String>> names) {
        this(names, null);
    }

    /** As {@link #RegistryNames(Map)}, sharing {@code previous}'s table for each registry whose names are equal. */
    public RegistryNames(Map<String, List<String>> names, RegistryNames previous) {
        var tables = new HashMap<String, IdTable>();
        names.forEach((registry, entries) -> {
            IdTable shared = previous == null ? null : previous.registries.get(registry);
            tables.put(
                    registry,
                    shared != null && shared.names().equals(entries) ? shared : new IdTable(registry, entries));
        });
        registries = Map.copyOf(tables);
    }

    public boolean contains(String registry) {
        return registries.containsKey(identifier(registry));
    }

    public int id(String registry, String name) {
        var table = registries.get(identifier(registry));
        return table == null ? -1 : table.id(identifier(name));
    }

    public int size(String registry) {
        return table(registry).size();
    }

    public String name(String registry, int id) {
        return table(registry).name(id);
    }

    public Map<String, IdTable> registries() {
        return registries;
    }

    private IdTable table(String registry) {
        return java.util.Objects.requireNonNull(
                registries.get(identifier(registry)), "Missing model registry " + registry);
    }

    private static String identifier(String name) {
        return name.indexOf(':') < 0 ? "minecraft:" + name : name;
    }
}
