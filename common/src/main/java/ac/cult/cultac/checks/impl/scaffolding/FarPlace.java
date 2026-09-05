package ac.cult.cultac.checks.impl.scaffolding;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.math.VectorUtils;
import net.minecraft.core.BlockPos;
import org.bukkit.Material;

@CheckData(name = "FarPlace", stableKey = "cult.scaffolding.far_place", description = "Placing blocks from too far away")
public class FarPlace extends BlockPlaceCheck {
    public FarPlace(CultPlayer player) {
        super(player);
    }

    @Override
    public void onBlockPlace(final BlockPlace place) {
        if (!player.cameraEntity.isSelf() || player.inVehicle()) return;

        BlockPos blockPos = place.getPlacedAgainstBlockLocation();

        if (place.getMaterial() == Material.SCAFFOLDING) return;

        double min = Double.MAX_VALUE;
        for (double d : player.getPossibleEyeHeights()) {
            SimpleCollisionBox box = new SimpleCollisionBox(blockPos);
            Vector3dm best = VectorUtils.cutBoxToVector(player.x, player.y + d, player.z, box);
            min = Math.min(min, best.distanceSquared(player.x, player.y + d, player.z));
        }

        // getPickRange() determines this?
        // With 1.20.5+ the new attribute determines creative mode reach using a modifier
        double maxReach = player.compensatedEntities.getSelf().getBlockInteractionRange();
        double threshold = player.getMovementThreshold();
        maxReach += Math.hypot(threshold, threshold);

        if (min > maxReach * maxReach) { // fail
            if (flag() && shouldModifyPackets() && shouldCancel()) {
                place.resync();
            }
        }
    }

}
