package ac.grim.grimac.checks.impl.prediction.stage.uncertainty;

import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.VectorUtils;
import ac.grim.grimac.utils.nmsutil.Friction;
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

        // restrict bouncing upwards
        if (start.isJump()) return start;

        float bounceLevel = getBouncyBlockLevel(player, onBlock);

        if (bounceLevel == 0) return start;

        // Find minY AABB
        SimpleCollisionBox aabb = lastContext.getValidMovements().getCollisionIgnoredMaxStartingVelExtents();
        for (PredictionResult reality : lastContext.getRealities()) {
            aabb.union(reality.getValidMovements().getCollisionIgnoredMaxStartingVelExtents());
        }

        double minYAmount = aabb.minY;
        // MCP-Reborn 26.2 Entity#restituteMovementAfterCollisions only bounces when
        // the impact speed reaches the entity's effective gravity; below it the
        // restitution is forced to zero.
        double gravity = player.compensatedEntities.getEntityInControl().gravity;
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2)) {
            if (player.compensatedEntities.getSlowFallingAmplifier() != null) {
                gravity = Math.min(gravity, 0.01D);
            }
            if (-minYAmount < gravity) return start;
        }
        // account for gravity
        minYAmount = minYAmount - gravity;

        if (minYAmount > 0) return start;

        // Living entities (such as the player) will bounce by 0.8, non-living by 1.0
        // 0.66F for beds, times this multiplier again specified above.
        double bounceMultiplier = player.compensatedEntities.getEntityInControl().isLivingEntity() ? 1.0 : 0.8;

        // MCP-Reborn 26.2 applies the entity's air drag to the restituted velocity
        // (lerp(portion, 1, airDrag), maximized at portion 1).
        double airDrag = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2)
                ? Friction.computeModifiedFriction(0.98F, (float) player.compensatedEntities.getEntityInControl().airDragModifier)
                : 1.0D;

        // The player can bounce this high
        double bounceAmount = minYAmount * -1 * bounceMultiplier * bounceLevel * airDrag;
        double bounceY = GrimMath.clamp(end.y, start.y, bounceAmount);
        return start.withY(bounceY, "bounce");
    }

    private float getBouncyBlockLevel(GrimPlayer player, Material onBlock) {
        if (onBlock == Material.SLIME_BLOCK) {
            return 1;
        } else if (NmsBlockTags.isBed(onBlock)) {
            // MCP-Reborn 26.2 Blocks.java registers beds with bounceRestitution 0.75
            return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2) ? 0.75F : 0.66F;
        }
        return 0;
    }
}
