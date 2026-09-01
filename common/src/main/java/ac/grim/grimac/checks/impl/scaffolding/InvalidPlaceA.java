package ac.grim.grimac.checks.impl.scaffolding;

import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.BlockPlaceCheck;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;
import net.minecraft.world.phys.Vec3;

@CheckData(name = "InvalidPlaceA", stableKey = "grim.scaffolding.invalid_place_a", description = "Sent invalid cursor position")
public class InvalidPlaceA extends BlockPlaceCheck {
    public InvalidPlaceA(GrimPlayer player) {
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
