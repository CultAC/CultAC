package ac.cult.cultac.events.packets;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;

/** Collects component definitions during client configuration; tags use server bindings. */
public class PacketServerRegistries {
    private static final boolean SERVER_HAS_TRANSFORMER_REGISTRIES = hasNativeTransformerRegistries();

    private static boolean hasNativeTransformerRegistries() {
        try {
            // BlockTransformer and its synchronized registries were introduced together
            // in RC1. A translated RC1 client does not add these classes to an older server.
            Class.forName("net.minecraft.core.component.BlockTransformer", false, PacketServerRegistries.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException olderServer) {
            return false;
        }
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket")
    public void onRegistryData(PacketSendEvent event, CultPlayer player, net.minecraft.network.protocol.Packet<?> packet) {
        if (!SERVER_HAS_TRANSFORMER_REGISTRIES || player.isBedrockMovement()
                || player.getClientVersion().isOlderThan(ac.cult.cultac.network.protocol.ClientVersion.V_26_3)) return;
        if (player.registryState == null) player.registryState = new ac.cult.cultac.utils.latency.ClientComponentRegistries();
        player.registryState.append((net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket) packet);
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket")
    public void onStartConfiguration(PacketSendEvent event, CultPlayer player, net.minecraft.network.protocol.Packet<?> packet) {
        player.registryState = null;
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket")
    public void onFinishConfiguration(PacketSendEvent event, CultPlayer player, net.minecraft.network.protocol.Packet<?> packet) {
        if (player.registryState != null) player.registryState.finish();
    }

}
