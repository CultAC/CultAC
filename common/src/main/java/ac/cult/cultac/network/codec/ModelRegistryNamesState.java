package ac.cult.cultac.network.codec;

import ac.cult.blocksim.environment.ClientWorldDefaults;
import ac.cult.cultac.protocol.data.RegistryNames;
import ac.cult.cultac.utils.latency.ClientWorldRegistries;
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
    private final Map<String, List<String>> pendingDefined = new HashMap<>();
    private RegistryNames names = defaults();
    private RegistryNames projecting;

    public RegistryNames snapshot() {
        return names;
    }

    /** The generation an older wire publishes; older-wire values project configuration tags against it. */
    public RegistryNames projecting() {
        if (pending.isEmpty()) return names;
        if (projecting == null) projecting = older();
        return projecting;
    }

    public void append(String registry, List<String> entries) {
        append(registry, entries, List.of());
    }

    /** {@code defined} names the entries that carry their own definition. */
    public void append(String registry, List<String> entries, List<String> defined) {
        var values = new ArrayList<>(pending.getOrDefault(registry, List.of()));
        values.addAll(entries);
        pending.put(registry, List.copyOf(values));
        var definitions = new ArrayList<>(pendingDefined.getOrDefault(registry, List.of()));
        definitions.addAll(defined);
        pendingDefined.put(registry, List.copyOf(definitions));
        projecting = null;
    }

    /** True once this configuration has received registry contents. */
    public boolean contentsReceived() {
        return !pending.isEmpty();
    }

    /**
     * The generation a finish_configuration publishes. With {@code older} it mirrors ClientWorldRegistries' older
     * generation, and a tags-only configuration retains the previous one.
     */
    public RegistryNames staged(boolean older) {
        if (older) return projecting();
        if (pending.isEmpty()) return names;
        var defaults = ClientWorldDefaults.defaults();
        var values = new HashMap<String, List<String>>();
        for (String registry : defaults.builtinRegistries()) values.put(registry, defaults.initialNames(registry));
        for (String registry : defaults.networkRegistries())
            values.put(registry, pending.getOrDefault(registry, List.of()));
        return new RegistryNames(values);
    }

    public void publish(RegistryNames generation) {
        names = generation;
        clear();
    }

    /** Model defaults with the client's dimension type order; received entries beyond the defaults never resolve. */
    public void fallback() {
        var values = new HashMap<>(ClientWorldDefaults.defaults().initialNames());
        var current = names.registries().get(ClientWorldRegistries.DIMENSIONS);
        if (pending.containsKey(ClientWorldRegistries.DIMENSIONS))
            values.put(ClientWorldRegistries.DIMENSIONS, pending.get(ClientWorldRegistries.DIMENSIONS));
        else if (current != null) values.put(ClientWorldRegistries.DIMENSIONS, current.names());
        publish(new RegistryNames(values));
    }

    private RegistryNames older() {
        var values = new HashMap<>(ClientWorldDefaults.defaults().initialNames());
        if (pending.containsKey(ClientWorldRegistries.DIMENSIONS))
            values.put(ClientWorldRegistries.DIMENSIONS, pending.get(ClientWorldRegistries.DIMENSIONS));
        for (String registry : ClientWorldRegistries.OLDER_RETAINED) {
            var appended =
                    ClientWorldRegistries.olderAppended(registry, pendingDefined.getOrDefault(registry, List.of()));
            if (appended.isEmpty()) continue;
            var entries = new ArrayList<>(values.get(registry));
            entries.addAll(appended);
            values.put(registry, List.copyOf(entries));
        }
        return new RegistryNames(values);
    }

    private void clear() {
        pending.clear();
        pendingDefined.clear();
        projecting = null;
    }
}
