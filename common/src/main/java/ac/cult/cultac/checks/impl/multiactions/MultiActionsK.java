package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;

@CheckData(
        name = "MultiActionsK",
        stableKey = "cult.multiactions.inventory_place",
        description = "Placed a block while in an inventory")
public class MultiActionsK extends BlockPlaceCheck {

    public MultiActionsK(CultPlayer player) {
        super(player);
    }

    @Override
    public void onBlockPlace(BlockPlace place) {
        if (player.openWindow.mustBeOpen() && flag() && shouldModifyPackets() && shouldCancel()) {
            place.resync();
            player.closeInventorySafely();
        }
    }
}
