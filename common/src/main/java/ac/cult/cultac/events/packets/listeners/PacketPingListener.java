package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.CultAPI;
import ac.grim.grimac.api.event.events.GrimTransactionReceivedEvent;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;

public class PacketPingListener {
    private static final class Channels {
        private static final GrimTransactionReceivedEvent.Channel RECEIVED =
                CultAPI.INSTANCE.getEventBus().get(GrimTransactionReceivedEvent.class);
    }

    @CultPacketHandler
    public void onPong(PacketReceiveEvent event, CultPlayer player, ServerboundPongPacket packet) {
        event.setAcceptedTransactionResponse(false);
        if (player.addTransactionResponse(packet.getId())) {
            event.setAcceptedTransactionResponse(true);
            boolean shouldCancel = !CultAPI.INSTANCE.getConfigManager().isDisablePongCancelling();
            // Not needed for vanilla as vanilla ignores this packet, needed for packet limiters
            event.setCancelled(shouldCancel);
            Channels.RECEIVED.fire(player, packet.getId(), shouldCancel, event.getTimestamp());
        }
    }

    @CultPacketHandler
    public void onPing(PacketSendEvent event, CultPlayer player, ClientboundPingPacket packet) {
        player.packetStateData.lastServerTransWasValid = false;
        if (player.markTransactionPacketSent(packet, event.getTimestamp())) {
            player.packetStateData.lastServerTransWasValid = true;
        }
    }
}
