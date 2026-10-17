package ac.cult.cultac.network;

import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.packet.ClientboundPackets;

/** One immutable publication, indexed by the runtime's canonical family slots. */
final class PacketRoutes {
    private final ProtocolRuntime runtime;
    private final PacketDispatcher.Route[] entries;

    PacketRoutes(ProtocolRuntime runtime, PacketDispatcher.Route[] routes) {
        this.runtime = runtime;
        entries = routes.clone();
        for (var type : ConnectionLifecycle.types()) structural(type);
        structural(ClientboundPackets.BUNDLE_DELIMITER);
        // The play login gives the client its level back; it is placed whether or not a listener wants it.
        structural(ClientboundPackets.LOGIN);
    }

    private void structural(PacketType<?> type) {
        if (runtime.supports(type) && entries[runtime.slot(type)] == null) {
            entries[runtime.slot(type)] = new PacketDispatcher.Route(type, null, null);
        }
    }

    PacketDispatcher.Route get(PacketDirection direction, ConnectionPhase phase, int id) {
        var binding = runtime.binding(phase, direction, id);
        return binding == null ? null : entries[binding.slot()];
    }

    PacketDispatcher.Route get(PacketType<?> type) {
        return runtime.contains(type) ? entries[runtime.slot(type)] : null;
    }
}
