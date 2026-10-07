package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.checks.impl.chat.ChatD;
import ac.cult.cultac.checks.impl.misc.ClientBrand;
import ac.cult.cultac.manager.player.PluginChannelManager;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundClientInformation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomPayload;

public class PacketConfigurationListener {

    @CultPacketHandler
    public void onCustomPayload(
            PacketReceiveEvent<ServerboundCustomPayload> event, CultPlayer player, ServerboundCustomPayload packet) {
        if (event.getPhase() != ConnectionPhase.CONFIGURATION) {
            return;
        }
        if (event.isCancelled()) {
            return;
        }

        String channelName = packet.channel();
        byte[] data = event.getPacket().data();
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
    public void onClientInformation(
            PacketReceiveEvent<ServerboundClientInformation> event,
            CultPlayer player,
            ServerboundClientInformation packet) {
        if (event.getPhase() != ConnectionPhase.CONFIGURATION) {
            return;
        }
        // PreViaCheckManagerListener dispatched configuration CLIENT_SETTINGS to ChatD.
        // Configuration and play use the same NMS packet class, but the play-only
        // CheckManagerListener gate deliberately does not see this phase.
        player.checkManager.getListener(ChatD.class).onClientInformation(event, player, packet);
    }
}
