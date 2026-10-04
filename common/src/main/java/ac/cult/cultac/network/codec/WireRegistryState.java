package ac.cult.cultac.network.codec;

import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.*;
import ac.cult.cultac.utils.minecraft.MinecraftRegistries;
import ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil;
import java.util.*;
import net.minecraft.core.Registry;

/** Connection-owned wire IDs. Shared decoders only borrow this lookup for one invocation. */
public final class WireRegistryState implements WireValueDecoder.Registries {
    private final ProtocolVersion version;
    private final ModelRegistryData source;
    private final MinecraftRegistries model;
    private final Map<String, List<String>> received = new HashMap<>();
    private final Set<String> unavailable = new HashSet<>();

    public WireRegistryState(ProtocolVersion version, MinecraftRegistries model) {
        this.version = version;
        this.source = ModelRegistryData.load(version);
        this.model = model;
    }

    public void beginConfiguration() {
        received.clear();
    }

    public void append(WireValueDecoder.RegistryValues packet) {
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

    public Registry<?> modelRegistry(String registry) {
        return model.access().lookupOrThrow(NmsIdentifierUtil.registryKey(registry));
    }

    public boolean hasModelRegistry(String registry) {
        return model.access().lookup(NmsIdentifierUtil.registryKey(registry)).isPresent();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public int modelId(String registry, String name) {
        Registry values =
                model.access().lookup(NmsIdentifierUtil.registryKey(registry)).orElse(null);
        if (values == null) return -1;
        var value = NmsIdentifierUtil.registryOptional(values, name);
        return value.isEmpty() ? -1 : values.getId(value.get());
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
