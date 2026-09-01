package ac.grim.grimac.events.packets.listeners;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.event.events.GrimTransactionReceivedEvent;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.event.PacketSendEvent;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;

public class PacketPingListener {
    private static final class Channels {
        private static final GrimTransactionReceivedEvent.Channel RECEIVED =
                GrimAPI.INSTANCE.getEventBus().get(GrimTransactionReceivedEvent.class);
    }

    @GrimPacketHandler
    public void onPong(PacketReceiveEvent event, GrimPlayer player, ServerboundPongPacket packet) {
        event.setAcceptedTransactionResponse(false);
        if (player.addTransactionResponse(packet.getId())) {
            event.setAcceptedTransactionResponse(true);
            boolean shouldCancel = !GrimAPI.INSTANCE.getConfigManager().isDisablePongCancelling();
            // Not needed for vanilla as vanilla ignores this packet, needed for packet limiters
            event.setCancelled(shouldCancel);
            Channels.RECEIVED.fire(player, packet.getId(), shouldCancel, event.getTimestamp());
        }
    }

    @GrimPacketHandler
    public void onPing(PacketSendEvent event, GrimPlayer player, ClientboundPingPacket packet) {
        player.packetStateData.lastServerTransWasValid = false;
        if (player.markTransactionPacketSent(packet, event.getTimestamp())) {
            player.packetStateData.lastServerTransWasValid = true;
        }
    }
}
