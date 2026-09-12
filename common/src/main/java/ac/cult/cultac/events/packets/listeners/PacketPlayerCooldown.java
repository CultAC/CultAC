package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.player.CultPlayer.TrackedTransaction;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil;
import net.minecraft.network.protocol.game.ClientboundCooldownPacket;


public class PacketPlayerCooldown {
    //HIGH
    @CultPacketHandler
    public void onCooldown(PacketSendEvent event, CultPlayer player, ClientboundCooldownPacket packet) {
        String group = NmsIdentifierUtil.cooldownGroup(packet);

        int proofTransaction = player.lastTransactionSent.get();
        TrackedTransaction trackedTransaction = player.createTrackedTransactionPacketForBundle();
        if (trackedTransaction != null) {
            proofTransaction = trackedTransaction.transaction();
            TrackedTransaction sentTransaction = trackedTransaction;
            event.getTasksAfterSend().add(() -> {
                player.user.writePacket(sentTransaction.packet());
                player.markTrackedTransactionPacketSent(sentTransaction);
            });
        }

        int lastTransactionSent = proofTransaction;
        int duration = NmsIdentifierUtil.cooldownDuration(packet);
        player.latencyUtils.addRealTimeTask(lastTransactionSent, () ->
                player.checkManager.getCompensatedCooldown().addCooldown(group, duration, lastTransactionSent));
    }
}
