package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.checks.impl.exploit.ExploitA;
import ac.cult.cultac.checks.impl.misc.ClientBrand;
import ac.cult.cultac.checks.impl.chat.ChatD;
import ac.cult.cultac.manager.player.PluginChannelManager;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

public class PacketConfigurationListener {

    @CultPacketHandler
    public void onCustomPayload(PacketReceiveEvent event, CultPlayer player, ServerboundCustomPayloadPacket packet) {
        if (event.getConnectionState() != ConnectionProtocol.CONFIGURATION) {
            return;
        }
        if (event.isCancelled()) {
            return;
        }

        player.checkManager.getListener(ExploitA.class).sanitizePluginMessage(event, packet);
        if (event.isCancelled()) {
            return;
        }

        CustomPacketPayload payload = packet.payload();
        String channelName = NmsPacketUtil.payloadChannel(payload);
        byte[] data = NmsPacketUtil.payloadData(event);
        if (channelName == null) {
            return;
        }
        if (!channelName.equalsIgnoreCase("minecraft:brand") && !channelName.equals("MC|Brand")) {
            player.checkManager.getListener(PluginChannelManager.class).handleChannelRegistered(channelName);
        }
        if (channelName.equals("MC|Brand") || channelName.equalsIgnoreCase("minecraft:brand")) {
            final ClientBrand clientBrand = player.checkManager.getListener(ClientBrand.class);
            clientBrand.handle(channelName, data);
        }
        final PluginChannelManager pluginChannels = player.checkManager.getListener(PluginChannelManager.class);
        pluginChannels.handle(channelName, data, event);
    }

    @CultPacketHandler
    public void onClientInformation(PacketReceiveEvent event, CultPlayer player, ServerboundClientInformationPacket packet) {
        if (event.getConnectionState() != ConnectionProtocol.CONFIGURATION) {
            return;
        }
        // PreViaCheckManagerListener dispatched configuration CLIENT_SETTINGS to ChatD.
        // Configuration and play use the same NMS packet class, but the play-only
        // CheckManagerListener gate deliberately does not see this phase.
        player.checkManager.getListener(ChatD.class).onClientInformation(event, player, packet);
    }

}
