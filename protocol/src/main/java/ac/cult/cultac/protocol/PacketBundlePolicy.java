package ac.cult.cultac.protocol;

import ac.cult.cultac.protocol.packet.ClientboundPackets;

/** Packets Cult must place outside physical bundles, rather than merely outside a shared flush proof. */
public final class PacketBundlePolicy {
    private PacketBundlePolicy() {}

    /**
     * Keepalives retain their direct network-thread dispatch. Play login and respawn replace client level/player
     * state and may render a loading frame from their handler. Protocol terminals cannot be bundle children at
     * all; disconnect and ClientCommonPacketListenerImpl#handleTransfer, which leaves for another server, end the
     * connection the same way. A packet's first position in a bundle does not make it standalone.
     */
    public static boolean requiresStandalone(PacketType<?> type) {
        return type == ClientboundPackets.KEEP_ALIVE
                || type == ClientboundPackets.DISCONNECT
                || type == ClientboundPackets.TRANSFER
                || type == ClientboundPackets.LOGIN
                || type == ClientboundPackets.RESPAWN
                || ConnectionLifecycle.handles(type);
    }
}
