package ac.grim.grimac.checks.impl.scaffolding;

import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.BlockPlaceCheck;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.math.VectorUtils;
import net.minecraft.core.BlockPos;
import org.bukkit.Material;

@CheckData(name = "FarPlace", stableKey = "grim.scaffolding.far_place", description = "Placing blocks from too far away")
public class FarPlace extends BlockPlaceCheck {
    public FarPlace(GrimPlayer player) {
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
