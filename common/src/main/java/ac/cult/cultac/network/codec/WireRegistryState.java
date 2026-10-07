package ac.cult.cultac.network.codec;

import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.*;
import java.util.*;

/** Connection-owned wire IDs. Shared decoders only borrow this lookup for one invocation. */
public final class WireRegistryState implements WireValueDecoder.Registries {
    private final ProtocolVersion version;
    private final ModelRegistryData source;
    private final java.util.function.Supplier<RegistryNames> model;
    private final Map<String, List<String>> received = new HashMap<>();
    private boolean awaitingRegistryData;
    private final Set<String> unavailable = new HashSet<>();

    public WireRegistryState(ProtocolVersion version, java.util.function.Supplier<RegistryNames> model) {
        this.version = version;
        this.source = ModelRegistryData.load(version);
        this.model = model;
    }

    public void beginConfiguration() {
        awaitingRegistryData = true;
    }

    public void append(WireValueDecoder.RegistryValues packet) {
        // A tags-only configuration retains original registries. The first contents
        // packet selects RegistryDataCollector's fresh-registry branch instead.
        if (awaitingRegistryData) {
            received.clear();
            awaitingRegistryData = false;
        }
        var entries = new ArrayList<>(received.getOrDefault(packet.registry(), List.of()));
        packet.entries().forEach(entry -> entries.add(entry.name()));
        received.put(packet.registry(), List.copyOf(entries));
    }

    @Override
    public String name(String registry, int id) {
        var entries = received.get(registry);
        if (entries == null) return source.registry(registry).name(id);
        if (id < 0 || id >= entries.size())
            throw new MalformedPacketException("Unknown wire " + registry + " ID " + id);
        return entries.get(id);
    }

    @Override
    public int id(String registry, String name) {
        var entries = received.get(registry);
        return entries == null ? source.registry(registry).id(name) : entries.indexOf(name);
    }

    public int size(String registry) {
        var entries = received.get(registry);
        return entries == null ? source.registry(registry).size() : entries.size();
    }

    public int modelSize(String registry) {
        return model.get().size(registry);
    }

    public boolean hasModelRegistry(String registry) {
        return model.get().contains(registry);
    }

    public int modelId(String registry, String name) {
        return model.get().id(registry, name);
    }

    public void unavailable(String registry, String name) {
        if (unavailable.add(registry + "/" + name))
            System.getLogger(getClass().getName())
                    .log(
                            System.Logger.Level.WARNING,
                            "Older backend {0}: unavailable model registry value {1}/{2}",
                            version,
                            registry,
                            name);
    }
}
