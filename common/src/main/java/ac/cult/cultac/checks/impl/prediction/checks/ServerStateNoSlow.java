package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.manager.tick.Tickable;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.nmsutil.IsUsingItem;

// TODO: Investigate the modern protocol around using items
public class ServerStateNoSlow extends CultProcessor implements PostPredictionListener, Tickable {
    public ServerStateNoSlow(CultPlayer player) {
        super(player);
    }

    int lastBukkitNoUseItem = 100;
    int ticksSlowed = 0;

    double buffer = 0;
    double bufferThreshold = 20;

    public int usingItemTicks = 0;

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (player.platformPlayer == null || !player.platformPlayer.hasServerAuthority()) return;
        if (predictionComplete.isTeleport()) return;
        if (predictionComplete.isExempt()) {
            ticksSlowed = 0;
            return;
        }

        boolean movementTooFastForUseItem =
                predictionComplete.getPredictionResult().isExceedsSlowedSpeed();

        if (movementTooFastForUseItem) {
            ticksSlowed = Math.min(3, ticksSlowed + 1);
        } else {
            ticksSlowed = Math.max(0, ticksSlowed - 1);
        }
    }

    private Hand hand = Hand.MAIN_HAND;

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        if (player.platformPlayer == null || !player.platformPlayer.hasServerAuthority()) return;
        hand = packet.hand();
    }

    @CultPacketHandler
    public void onSetCarriedItem(
            PacketReceiveEvent<ServerboundSetCarriedItem> event, CultPlayer player, ServerboundSetCarriedItem packet) {
        if (player.platformPlayer == null || !player.platformPlayer.hasServerAuthority()) return;
        if (bufferThreshold != Integer.MAX_VALUE && hand != Hand.OFF_HAND) {
            IsUsingItem.stopUseItem(player);
        }
    }

    @Override
    public void reload() {
        super.reload();
        bufferThreshold = getConfig().getIntElse("cult.prediction.use-item-buffer", 20);
        if (bufferThreshold <= 0) {
            bufferThreshold = Integer.MAX_VALUE;
        }
    }

    public void tick() {
        if (player.platformPlayer == null || !player.platformPlayer.hasServerAuthority()) return;
        boolean bukkitUsingItem = IsUsingItem.isUsingItem(player);

        if (!bukkitUsingItem) {
            lastBukkitNoUseItem++;
            this.usingItemTicks = 0;
        } else {
            lastBukkitNoUseItem = 0;
            ++this.usingItemTicks;
        }

        // use to be <= 1, but a player quickly tapping could trigger this quite easily
        if (this.lastBukkitNoUseItem <= 0) {
            if (ticksSlowed <= 2) {
                // pass
                buffer = Math.max(0, buffer - 0.2);
            } else {
                // fail
                buffer = Math.min(bufferThreshold, buffer + 1);
                if (buffer >= bufferThreshold && player.platformPlayer != null) {
                    // TODO: Transform to be thread safe!
                    IsUsingItem.stopUseItem(player);
                    if (player.debugNoSlow) {
                        player.sendMessage("stopped using item, buffer=" + this.buffer);
                    }
                }
            }
        }
    }
}
