package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.PacketRouteBuilder;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundCustomPayload;
import ac.cult.cultac.utils.latency.ClientInterpolationOptIn;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps CultAC the only source of the linear interpolation offer: register and unregister frames from the proxy or a
 * backend reach the client without the configured channels, the session's channels and
 * {@link ClientInterpolationOptIn#INTERPOLATION_FIX_CHANNEL}.
 */
public final class PacketServerChannels {
    private PacketServerChannels() {}

    public static void register(PacketRouteBuilder routes) {
        routes.sendConnection(
                ClientboundPackets.CUSTOM_PAYLOAD, PacketListenerPriority.LOWEST, PacketServerChannels::onCustomPayload);
    }

    /** CultAC's own offer is written silently and never reaches this handler. */
    public static void onCustomPayload(PacketSendEvent<ClientboundCustomPayload> event, ClientboundCustomPayload packet) {
        String channel = packet.channel();
        if (!ClientboundPackets.REGISTER_CHANNEL.equals(channel)
                && !ClientboundPackets.UNREGISTER_CHANNEL.equals(channel)) {
            return;
        }
        List<String> configured = CultAPI.INSTANCE.getConfigManager().getLinearInterpolationChannels();
        CultPlayer player = event.getUser() == null ? null : event.getUser().getCultPlayer();
        List<String> session = player == null ? null : player.interpolationOptIn.sessionChannelsIfFixed();
        String[] channels = new String(packet.data(), StandardCharsets.UTF_8).split("\0", -1);
        List<String> kept = new ArrayList<>(channels.length);
        boolean removed = false;
        boolean remaining = false;
        for (String listed : channels) {
            if (configured.contains(listed)
                    || (session != null && session.contains(listed))
                    || ClientInterpolationOptIn.INTERPOLATION_FIX_CHANNEL.equals(listed)) {
                removed = true;
                continue;
            }
            kept.add(listed);
            if (!listed.isBlank()) remaining = true;
        }
        if (!removed) return;
        if (!remaining) {
            event.cancelFinally();
            return;
        }
        event.replace(new ClientboundCustomPayload(
                channel, String.join("\0", kept).getBytes(StandardCharsets.UTF_8)));
    }
}
