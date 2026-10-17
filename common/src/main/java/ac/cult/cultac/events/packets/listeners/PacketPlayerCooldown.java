package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.player.CultPlayer.TrackedTransaction;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundCooldown;

public class PacketPlayerCooldown {
    // HIGH
    @CultPacketHandler
    public void onCooldown(PacketSendEvent<ClientboundCooldown> event, CultPlayer player, ClientboundCooldown packet) {
        String group = packet.group();

        TrackedTransaction trackedTransaction = player.appendTrailingProofTransaction(event);
        int lastTransactionSent =
                trackedTransaction == null ? player.lastTransactionSent.get() : trackedTransaction.transaction();
        // A use start reads the cooldown the client handled; handleBundlePacket applies it and the proof together.
        if (trackedTransaction != null && !event.isInsideBundle()) event.bundle();
        int duration = packet.duration();
        player.latencyUtils.addRealTimeTask(
                lastTransactionSent,
                () -> player.checkManager.getCompensatedCooldown().addCooldown(group, duration, lastTransactionSent));
    }
}
