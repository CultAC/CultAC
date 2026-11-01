package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.ItemUseState;

public final class IsUsingItem {
    private IsUsingItem() {}

    public static boolean isUsingItem(CultPlayer player) {
        return state(player).active();
    }

    private static ItemUseState state(CultPlayer player) {
        ItemUseState state = player.platformPlayer == null ? null : player.platformPlayer.getItemUseState();
        return state == null ? ItemUseState.NONE : state;
    }
}
