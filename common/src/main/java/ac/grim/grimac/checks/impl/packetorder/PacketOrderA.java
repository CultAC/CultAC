package ac.grim.grimac.checks.impl.packetorder;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.inventory.inventory.WindowClickType;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;

@CheckData(name = "PacketOrderA", stableKey = "grim.packetorder.window_click_order", description = "Sent pickup and quick-move inventory clicks in an invalid order", experimental = true)
public class PacketOrderA extends Check implements PostPredictionListener {
    public PacketOrderA(final GrimPlayer player) {
        super(player);
    }

    private int invalid;

    @GrimPacketHandler
    public void onContainerClick(PacketReceiveEvent event, GrimPlayer player, ServerboundContainerClickPacket packet) {
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
