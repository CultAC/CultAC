package ac.cult.cultac.network.codec;

import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.data.RegistryNames;
import java.util.List;
import java.util.Objects;

/**
 * One connection's model registry names and older-wire value decoder. Every platform whose
 * observed wire can differ from the 26.3 model owns one, so both use the same projection.
 */
public final class ConnectionModelValues {
    private final ModelRegistryNamesState names = new ModelRegistryNamesState();
    private final WireValueDecoder decoder;
    private ObservedPacketValues values;

    public ConnectionModelValues(WireValueDecoder decoder) {
        this.decoder = decoder;
    }

    public RegistryNames registryNames() {
        return names.snapshot();
    }

    /** Created on first older-wire value; the dimension and paintings come from the attached player, if any. */
    public ObservedPacketValues packetValues(ProtocolVersion observed, CultConnection connection) {
        if (values == null)
            values = new ObservedPacketValues(
                    decoder,
                    Objects.requireNonNull(observed),
                    names::projecting,
                    name -> {
                        var player = connection.player();
                        return player == null
                                ? null
                                : player.getWorldRegistries().painting(name);
                    },
                    () -> {
                        var player = connection.player();
                        if (player == null || player.compensatedWorld.isClientboundUnresolved()) return null;
                        var dimension = player.compensatedWorld.getLastClientboundDimension();
                        return new int[] {dimension.minHeight(), dimension.sectionCount() * 16};
                    });
        return values;
    }

    public void append(String registry, List<WireValueDecoder.RegistryEntry> entries) {
        names.append(
                registry,
                entries.stream().map(WireValueDecoder.RegistryEntry::name).toList(),
                entries.stream()
                        .filter(entry -> entry.data() != null)
                        .map(WireValueDecoder.RegistryEntry::name)
                        .toList());
    }

    /** A finish_configuration generation: model names and the configuration tags bound against them. */
    public record Staged(RegistryNames names, RegistryTags tags) {}

    /**
     * The model wire publishes received registries; an older wire keeps model defaults plus its own entries and
     * binds its configuration tags against them. Nothing is published until {@link #publish}.
     */
    public Staged stage(ProtocolVersion decoded) {
        if (decoded == ProtocolVersion.V26_3) return new Staged(names.staged(false), null);
        var generation = names.staged(true);
        return new Staged(generation, values == null ? null : values.bindConfigurationTags(names.contentsReceived()));
    }

    public void publish(Staged staged) {
        names.publish(staged.names());
        if (values != null) values.clearConfigurationTags();
    }

    /** Publishes model defaults after a generation failed to stage. */
    public void fallback() {
        names.fallback();
        if (values != null) values.clearConfigurationTags();
    }

    public void beginConfiguration() {
        if (values != null) values.beginConfiguration();
    }
}
