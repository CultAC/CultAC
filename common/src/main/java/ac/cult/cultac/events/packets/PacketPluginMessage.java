package ac.cult.cultac.events.packets;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.protocol.util.viaversion.ViaVersionUtil;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomPayload;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.cult.cultac.utils.common.arguments.CommonCultArguments;

public class PacketPluginMessage {

    public void handle(PacketReceiveEvent<ServerboundCustomPayload> event, ServerboundCustomPayload packet) {
        checkChannel(event.getUser(), packet.channel());
    }

    private void checkChannel(User user, String channelName) {
        if (!"vv:proxy_details".equals(channelName)) return;
        final boolean usingProxy = isUsingProxy();
        if (usingProxy) {
            LogUtil.warn(
                    user.getName() + " seems to have connected through a proxy running ViaVersion. "
                            + "Having ViaVersion installed on the proxy is incompatible with CultAC and causes many issues. "
                            + "Please remove ViaVersion from your proxy server and install it on your backend servers instead.");
        }
        // kick if they do not have a proxy configured OR they have ViaVersion installed on the backend
        if (CommonCultArguments.KICK_ON_VIA_PROXY.value()
                && !CultAPI.INSTANCE.getConfigManager().isObserveOnly()
                && (!usingProxy || ViaVersionUtil.isAvailable())) {

            LogUtil.warn(user.getName() + " is being disconnected for sending ViaVersion proxy data.");

            try {
                user.write(new CultWrite(
                        MessageUtil.disconnectPacket(MessageUtil.miniMessage(
                                CultAPI.INSTANCE.getConfigManager().getDisconnectPacketError())),
                        false));
            } catch (Exception e) {
                LogUtil.warn("Failed to send disconnect packet to kick " + user.getName() + "!");
            }
            user.closeConnection();
        }
    }

    private static boolean isUsingProxy() {
        return CultAPI.INSTANCE.getPlatformServer().isProxyForwardingEnabled();
    }
}
