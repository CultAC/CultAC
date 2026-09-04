package ac.grim.grimac.checks.impl.prediction.stage.uncertainty;

import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.VectorUtils;
import ac.grim.grimac.utils.nmsutil.NmsBlockTags;
import org.bukkit.Material;
import net.minecraft.world.phys.Vec3;

public class BouncyBlock implements UncertaintyHandler{
    @Override
    public PredVector handleUncertainty(GrimPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastContext, PredVector start, Vec3 end) {
        if (lastContext == null || start.isKnockback()) return start;

        Material onBlock = lastContext.getSimulationContext().getWorldData().getOnBlock();

        // This could be improved...
        //
        // Player velocity can multiply 0.4-0.45 (guess on max) when the player is on slime with
        // a Y velocity of 0 to 0.1.  Because 0.03 we don't know this so just give lenience here
        if (onBlock == Material.SLIME_BLOCK) {
            SimpleCollisionBox expandToZero = new SimpleCollisionBox(start, start);
            expandToZero.unionX(0);
            expandToZero.unionZ(0);
            Vec3 cut = VectorUtils.cutBoxToVector(end, expandToZero);
            start = start.withXYZ(cut.x, cut.y, cut.z, "Slime block reduction");
        }

        // 26.2 vertical restitution is calculated once from Entity#move
        // collision state when deriving end-of-tick velocity in
        // VelocityCandidates. SlimeBlock#stepOn horizontal reduction above is
        // a separate mechanic and still applies in 26.2.
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2)) return start;

        // restrict bouncing upwards
        if (start.isJump()) return start;

        float bounceLevel = getBouncyBlockLevel(onBlock);

        if (bounceLevel == 0) return start;

        // Find minY AABB
        SimpleCollisionBox aabb = lastContext.getValidMovements().getCollisionIgnoredMaxStartingVelExtents();
        for (PredictionResult reality : lastContext.getRealities()) {
            aabb.union(reality.getValidMovements().getCollisionIgnoredMaxStartingVelExtents());
        }

        double minYAmount = aabb.minY;
        double gravity = player.compensatedEntities.getEntityInControl().gravity;
        // account for gravity
        minYAmount = minYAmount - gravity;

        if (minYAmount > 0) return start;

        // Living entities (such as the player) will bounce by 0.8, non-living by 1.0
        // 0.66F for beds, times this multiplier again specified above.
        double bounceMultiplier = player.compensatedEntities.getEntityInControl().isLivingEntity() ? 1.0 : 0.8;

        // The player can bounce this high
        double bounceAmount = minYAmount * -1 * bounceMultiplier * bounceLevel;
        double bounceY = GrimMath.clamp(end.y, start.y, bounceAmount);
        return start.withY(bounceY, "bounce");
    }

    private float getBouncyBlockLevel(Material onBlock) {
        if (onBlock == Material.SLIME_BLOCK) {
            return 1;
        } else if (NmsBlockTags.isBed(onBlock)) {
            return 0.66F;
        }
        return 0;
    }
}
