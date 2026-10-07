package ac.cult.cultac.network.codec;

import ac.cult.blocksim.environment.ClientWorldDefaults;
import ac.cult.cultac.protocol.data.RegistryNames;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Model ID generations, published at the same boundary as the existing registry loader. */
public final class ModelRegistryNamesState {
    private static final class Defaults {
        static final RegistryNames VALUE =
                new RegistryNames(ClientWorldDefaults.defaults().initialNames());
    }

    public static RegistryNames defaults() {
        return Defaults.VALUE;
    }

    private final Map<String, List<String>> pending = new HashMap<>();
    private RegistryNames names = defaults();

    public RegistryNames snapshot() {
        return names;
    }

    public void append(String registry, List<String> entries) {
        var values = new ArrayList<>(pending.getOrDefault(registry, List.of()));
        values.addAll(entries);
        pending.put(registry, List.copyOf(values));
    }

    public void finish() {
        if (!pending.isEmpty()) {
            var defaults = ClientWorldDefaults.defaults();
            var values = new HashMap<String, List<String>>();
            for (String registry : defaults.builtinRegistries()) values.put(registry, defaults.initialNames(registry));
            for (String registry : defaults.networkRegistries())
                values.put(registry, pending.getOrDefault(registry, List.of()));
            names = new RegistryNames(values);
        }
        pending.clear();
    }

    /** Mirrors the existing older-model producer: model defaults plus this configuration's dimensions. */
    public void finishOlder() {
        if (pending.containsKey("minecraft:dimension_type")) {
            var values = new HashMap<>(ClientWorldDefaults.defaults().initialNames());
            values.put("minecraft:dimension_type", pending.get("minecraft:dimension_type"));
            names = new RegistryNames(values);
        } else names = defaults();
        pending.clear();
    }
}
