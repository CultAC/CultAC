package ac.cult.cultac.network.codec;

import ac.cult.cultac.network.CultConnection;
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

    /** Created on first older-wire value; the dimension and paintings come from the attached player. */
    public ObservedPacketValues packetValues(ProtocolVersion observed, CultConnection connection) {
        if (values == null)
            values = new ObservedPacketValues(
                    decoder,
                    Objects.requireNonNull(observed),
                    names::snapshot,
                    name -> connection.player().getWorldRegistries().painting(name),
                    () -> {
                        var player = connection.player();
                        if (player == null) throw new IllegalStateException("Chunk before player initialization");
                        var dimension = player.compensatedWorld.getLastClientboundDimension();
                        return new int[] {dimension.minHeight(), dimension.sectionCount() * 16};
                    });
        return values;
    }

    public void append(String registry, List<String> entries) {
        names.append(registry, entries);
    }

    /** The model wire publishes received registries; an older wire keeps model defaults and its dimensions. */
    public void finish(ProtocolVersion decoded) {
        if (decoded == ProtocolVersion.V26_3) names.finish();
        else names.finishOlder();
    }

    public void beginConfiguration() {
        if (values != null) values.beginConfiguration();
    }
}
