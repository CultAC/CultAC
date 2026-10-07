package ac.cult.cultac.network;

import ac.cult.cultac.network.codec.ClientPacketCodecs;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.utils.latency.ClientWorldRegistries;
import java.util.List;

/** Production catalog using the bundled model tables for offline fixtures. */
public final class TestProtocolRuntime {
    private TestProtocolRuntime() {}

    public static ProtocolRuntime create(ProtocolData data) {
        return ProtocolRuntime.create(data, catalog());
    }

    public static List<PacketType<?>> catalog() {
        return ClientPacketCodecs.catalog(
                context -> ModelRegistryNamesState.defaults(),
                context -> context.state().require(ClientWorldRegistries.class));
    }
}
