package ac.grim.grimac.checks.impl.prediction.checks;

import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.manager.tick.Tickable;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.nmsutil.IsUsingItem;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;

// TODO: Investigate the modern protocol around using items
public class ServerStateNoSlow extends GrimProcessor implements PostPredictionListener, Tickable {
    public ServerStateNoSlow(GrimPlayer player) {
        super(player);
    }

    int lastBukkitNoUseItem = 100;
    int ticksSlowed = 0;

    double buffer = 0;
    double bufferThreshold = 20;

    public int usingItemTicks = 0;


    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (predictionComplete.isTeleport()) return;
        if (predictionComplete.isExempt()) {
            ticksSlowed = 0;
            return;
        }

        boolean movementTooFastForUseItem = predictionComplete.getPredictionResult().isExceedsSlowedSpeed();

        if (movementTooFastForUseItem) {
            ticksSlowed = Math.min(3, ticksSlowed + 1);
        } else {
            ticksSlowed = Math.max(0, ticksSlowed - 1);
        }
    }

    private InteractionHand hand = InteractionHand.MAIN_HAND;

    @GrimPacketHandler
    public void onUseItem(PacketReceiveEvent event, GrimPlayer player, ServerboundUseItemPacket packet) {
        hand = packet.getHand();
    }

    @GrimPacketHandler
    public void onSetCarriedItem(PacketReceiveEvent event, GrimPlayer player, ServerboundSetCarriedItemPacket packet) {
        if (bufferThreshold != Integer.MAX_VALUE && hand != InteractionHand.OFF_HAND) {
            IsUsingItem.stopUseItem(player);
        }
    }

    @Override
    public void reload() {
        super.reload();
        bufferThreshold = getConfig().getIntElse("use-item-buffer", 20);
        if (bufferThreshold <= 0) {
            bufferThreshold = Integer.MAX_VALUE;
        }
    }

    public void tick() {
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
                if (buffer >= bufferThreshold && player.bukkitPlayer != null) {
                    // TODO: Transform to be thread safe!
                    IsUsingItem.stopUseItem(player);
                    if (player.debugNoSlow) { player.sendMessage("stopped using item, buffer=" + this.buffer); }
                }
            }
        }
    }
}
