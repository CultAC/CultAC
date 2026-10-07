package ac.cult.cultac.protocol.data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable name/ID snapshot. No native values or live registries cross the boundary. */
public final class RegistryNames {
    private final Map<String, IdTable> registries;

    public RegistryNames(Map<String, List<String>> names) {
        var tables = new HashMap<String, IdTable>();
        names.forEach((registry, entries) -> tables.put(registry, new IdTable(registry, entries)));
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
