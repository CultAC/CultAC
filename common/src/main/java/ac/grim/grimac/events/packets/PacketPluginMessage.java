package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.network.PacketReceiveHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.network.protocol.player.User;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import ac.grim.grimac.utils.common.arguments.CommonGrimArguments;
import ac.grim.grimac.utils.viaversion.ViaVersionUtil;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

import java.io.File;


public class PacketPluginMessage implements PacketReceiveHandler<Packet<?>> {

    @Override
    public void handle(PacketReceiveEvent event, GrimPlayer player, Packet<?> packet) {
        // One NMS class carries both the play- and configuration-phase custom payload.
        if (!(packet instanceof ServerboundCustomPayloadPacket customPayload)) return;
        String channelName = NmsPacketUtil.payloadChannel(customPayload.payload());
        if (channelName == null) return;
        checkChannel(event.getUser(), channelName);
    }

    private void checkChannel(User user, String channelName) {
        if (!"vv:proxy_details".equals(channelName)) return;
        final boolean usingProxy = isUsingProxy();
        // warn if they are using a proxy
        if (usingProxy) {
            LogUtil.warn(
                    user.getName() + " seems to have connected through a proxy running ViaVersion. "
                            + "Having ViaVersion installed on the proxy is incompatible with GrimAC and causes many issues. "
                            + "Please remove ViaVersion from your proxy server and install it on your backend servers instead."
            );
        }
        // kick if they do not have a proxy configured OR they have ViaVersion installed on the backend
        if (CommonGrimArguments.KICK_ON_VIA_PROXY.value() && (!usingProxy || ViaVersionUtil.isAvailable)) {

            LogUtil.warn(user.getName() + " is being disconnected for sending ViaVersion proxy data.");

            try {
                ClientboundDisconnectPacket disconnect = new ClientboundDisconnectPacket(
                        toNmsComponent(MessageUtil.miniMessage(GrimAPI.INSTANCE.getConfigManager().getDisconnectPacketError()))
                );
                user.sendPacket(disconnect);
            } catch (Exception e) {
                LogUtil.warn("Failed to send disconnect packet to kick " + user.getName() + "!");
            }
            user.closeConnection();
        }
    }


    private static boolean isUsingProxy() {
        return getBooleanFromFile("spigot.yml", "settings.bungeecord")
                || getBooleanFromFile("paper.yml", "settings.velocity-support.enabled")
                || getBooleanFromFile("config/paper-global.yml", "proxies.velocity.enabled");
    }

    private static boolean getBooleanFromFile(String pathToFile, String pathToValue) {
        File file = new File(pathToFile);
        if (!file.exists()) return false;
        return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file).getBoolean(pathToValue);
    }

    // Same adventure->NMS component fallback as GrimPlayer#disconnect.
    private static net.minecraft.network.chat.Component toNmsComponent(net.kyori.adventure.text.Component reason) {
        if (reason instanceof TranslatableComponent translatableComponent) {
            return net.minecraft.network.chat.Component.translatable(translatableComponent.key());
        }
        String text = LegacyComponentSerializer.legacySection().serialize(reason);
        return net.minecraft.network.chat.Component.literal(MessageUtil.stripColor(text));
    }
}
