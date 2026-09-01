package ac.grim.grimac.events.packets.listeners;

import ac.grim.grimac.checks.impl.exploit.ExploitA;
import ac.grim.grimac.checks.impl.misc.ClientBrand;
import ac.grim.grimac.checks.impl.chat.ChatD;
import ac.grim.grimac.manager.player.PluginChannelManager;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

public class PacketConfigurationListener {

    @GrimPacketHandler
    public void onCustomPayload(PacketReceiveEvent event, GrimPlayer player, ServerboundCustomPayloadPacket packet) {
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

    @GrimPacketHandler
    public void onClientInformation(PacketReceiveEvent event, GrimPlayer player, ServerboundClientInformationPacket packet) {
        if (event.getConnectionState() != ConnectionProtocol.CONFIGURATION) {
            return;
        }
        // PreViaCheckManagerListener dispatched configuration CLIENT_SETTINGS to ChatD.
        // Configuration and play use the same NMS packet class, but the play-only
        // CheckManagerListener gate deliberately does not see this phase.
        player.checkManager.getListener(ChatD.class).onClientInformation(event, player, packet);
    }

}
