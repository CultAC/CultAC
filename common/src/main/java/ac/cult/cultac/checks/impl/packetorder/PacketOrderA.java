package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;

@CheckData(name = "PacketOrderA", stableKey = "cult.packetorder.window_click_order", description = "Sent pickup and quick-move inventory clicks in an invalid order", experimental = true)
public class PacketOrderA extends Check implements PostPredictionListener {
    public PacketOrderA(final CultPlayer player) {
        super(player);
    }

    private int invalid;

    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent event, CultPlayer player, ServerboundContainerClickPacket packet) {
        final WindowClickType clickType = NmsPacketUtil.readContainerClick(packet).clickType();

        if ((clickType == WindowClickType.PICKUP || clickType == WindowClickType.PICKUP_ALL) && player.packetOrderProcessor.isQuickMoveClicking()
                || clickType == WindowClickType.QUICK_MOVE && player.packetOrderProcessor.isPickUpClicking()) {
            if (!player.canSkipTicks()) {
                if (flag() && shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            } else {
                invalid++;
            }
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!player.canSkipTicks()) return;

        if (player.isTickingReliablyFor(3)) {
            for (; invalid >= 1; invalid--) {
                flag();
            }
        }

        invalid = 0;
    }
}
