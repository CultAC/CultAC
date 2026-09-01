package ac.grim.grimac.checks.impl.multiactions;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.BlockBreakListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import net.minecraft.world.InteractionHand;

@CheckData(name = "MultiActionsB", stableKey = "grim.multiactions.break_while_using", description = "Breaking blocks while using an item", experimental = true)
public class MultiActionsB extends Check implements BlockBreakListener {
    public MultiActionsB(GrimPlayer player) {
        super(player);
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (isActivelyUsingItem()) {
            // this is vanilla on 1.7
            if (player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_7_10)) {
                return;
            }

            if (flag() && shouldModifyPackets()) {
                blockBreak.cancel();
            }
        }
    }

    // Limit the active item to the in-use hand's current slot.
    private boolean isActivelyUsingItem() {
        return player.packetStateData.isSlowedByUsingItem()
                && (player.packetStateData.lastSlotSelected == player.packetStateData.getSlowedByUsingItemSlot()
                || player.packetStateData.itemInUseHand == InteractionHand.OFF_HAND);
    }
}
