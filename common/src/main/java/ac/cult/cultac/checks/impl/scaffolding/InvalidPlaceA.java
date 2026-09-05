package ac.cult.cultac.checks.impl.scaffolding;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import net.minecraft.world.phys.Vec3;

@CheckData(name = "InvalidPlaceA", stableKey = "cult.scaffolding.invalid_place_a", description = "Sent invalid cursor position")
public class InvalidPlaceA extends BlockPlaceCheck {
    public InvalidPlaceA(CultPlayer player) {
        super(player);
    }

    @Override
    public void onBlockPlace(final BlockPlace place) {
        Vec3 cursor = place.getCursor();
        if (cursor == null) return;
        if (!Double.isFinite(cursor.x) || !Double.isFinite(cursor.y) || !Double.isFinite(cursor.z)) {
            if (flag() && shouldModifyPackets() && shouldCancel()) {
                place.resync();
            }
        }
    }

}
