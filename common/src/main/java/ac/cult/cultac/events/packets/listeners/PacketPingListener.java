package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.grim.grimac.api.event.events.GrimTransactionReceivedEvent;

public class PacketPingListener {
    public static boolean acceptBedrockResponse(CultPlayer player, int id) {
        if (!player.addTransactionResponse(id)) return false;
        player.checkManager.getCheck(TimerCheck.class).onTransactionResponse();
        if (!Channels.RECEIVED.isEmpty()) {
            Channels.RECEIVED.fire(RuntimeEnvironment.outwardUser(player), id, true, System.currentTimeMillis());
        }
        return true;
    }

    private static final class Channels {
        private static final GrimTransactionReceivedEvent.Channel RECEIVED =
                CultAPI.INSTANCE.getEventBus().get(GrimTransactionReceivedEvent.class);
    }

    @CultPacketHandler
    public void onPong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
        event.setAcceptedTransactionResponse(false);
        if (player.addTransactionResponse(packet.id())) {
            event.setAcceptedTransactionResponse(true);
            boolean shouldCancel = !CultAPI.INSTANCE.getConfigManager().isDisablePongCancelling();
            // Not needed for vanilla as vanilla ignores this packet, needed for packet limiters
            if (shouldCancel) event.cancelFinally();
            else event.setCancelled(false);
            if (!Channels.RECEIVED.isEmpty()) {
                Channels.RECEIVED.fire(
                        RuntimeEnvironment.outwardUser(player), packet.id(), shouldCancel, event.getTimestamp());
            }
        } else if (!player.user.getCultConnection().holdsRetiredPing(packet.id())) {
            player.foreignPongReceived(packet.id());
        }
    }

    /** Connection scope: a pong answering a ping CultAC wrote for a player since removed from the connection. */
    public void onRetiredPong(PacketReceiveEvent<ServerboundPong> event, ServerboundPong packet) {
        if (!event.getUser().getCultConnection().takeRetiredPing(packet.id())) return;
        event.setAcceptedTransactionResponse(true);
        if (!CultAPI.INSTANCE.getConfigManager().isDisablePongCancelling()) event.cancelFinally();
    }

    @CultPacketHandler
    public void onPing(PacketSendEvent<ClientboundPing> event, CultPlayer player, ClientboundPing packet) {
        player.packetStateData.lastServerTransWasValid = false;
        int transaction = player.markTrackedPingSent(packet.id(), event.getTimestamp());
        if (transaction > 0) {
            player.packetStateData.lastServerTransWasValid = true;
            event.trackedPing(transaction);
        } else {
            player.foreignPingSent(packet.id());
        }
    }
}
