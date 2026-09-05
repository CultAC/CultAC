package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.VectorUtils;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.NmsBlockTags;
import org.bukkit.Material;
import net.minecraft.world.phys.Vec3;

public class PointThree implements UncertaintyHandler {

    @Override
    public PredVector handleUncertainty(CultPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, PredVector start, Vec3 end) {
        double offsetHorizontal = getOffsetHorizontal(player, start, context, lastResult);
        start = UncertaintyHelper.handleCircular(start, end, offsetHorizontal);

        if (start.isTickSkip() && player.compensatedEntities.getLevitationAmplifier() != null) {
            double maxY = 0.05 * (player.compensatedEntities.getLevitationAmplifier() + 1) * 0.2;
            if (end.y > start.y) {
                start = UncertaintyHelper.handleVertical(start, end, maxY);
            }
        }

        double threshold = player.getMovementThreshold();
        boolean lastControlVertical = false;
        boolean lastGravity = false;

        if (lastResult != null) {
            double multiplier = context.getTargetScalarVert();
            threshold *= multiplier;
            lastControlVertical = lastResult.anyRealityControlsVerticalMovement();
            lastGravity = lastResult.anyRealityDidTickSkipGravity();
        }

        boolean canVerticalTickSkip = start.isTickSkip();
        boolean controlsVerticalMovement = canVerticalTickSkip && controlsVerticalMovement(player, result);
        boolean recoveringTickSkip = context.getLastTickSkip().getRaw() == 1;
        boolean onGroundFuckery = player.checkManager.getSimulationProcessor().getLastOnGroundSkip().hasOccurredSince(1);

        if (controlsVerticalMovement) {
            result.getValidMovements().setControlsVerticalMovement(true);
        }

        if (controlsVerticalMovement && start.isKnockback() && !start.isTickSkip()) {
            start = UncertaintyHelper.handleVertical(start, end, threshold);
        } else if (canVerticalTickSkip && end.y < start.y) { // we want to go down
            SimpleCollisionBox box = new SimpleCollisionBox(start, start);
            // Maximum amount skipped when falling
            double gravity = player.compensatedEntities.getEntityInControl().gravity;
            if (start.y <= 0.0D && context.getEntities().getSlowFallingAmplifier() != null) {
                gravity = Math.min(gravity, 0.01D);
            }
            double verticalDrag = player.getClientVersion().isNewerThanOrEquals(ac.cult.cultac.network.protocol.ClientVersion.V_26_2)
                    ? ac.cult.cultac.utils.nmsutil.Friction.computeModifiedFriction(0.98F,
                    (float) player.compensatedEntities.getEntityInControl().airDragModifier)
                    : 0.98F;
            box.unionY((-gravity - threshold * 2) * verticalDrag);
            start = start.with(VectorUtils.cutBoxToVector(end, box), "gravity missing");

            if (!valid.isTestingMaxStartingVelExtents(end)) {
                result.getValidMovements().setDidTickSkipGravity(true);
            }
        } else if (controlsVerticalMovement) {
            start = UncertaintyHelper.handleVertical(start, end, threshold);
        } else if (recoveringTickSkip || onGroundFuckery) {
            // We have an area of movement threshold that we don't know
            if (lastControlVertical || lastGravity || onGroundFuckery) {
                start = UncertaintyHelper.handleVertical(start, end, threshold);
            } else if (end.y < start.y) { // Only allow going down
                start = UncertaintyHelper.handleVertical(start, end, threshold);
            }
        }

        return start;
    }

    private boolean controlsVerticalMovement(CultPlayer player, PredictionResult result) {
        if (result == null) return false;

        WorldData data = result.getSimulationContext().getWorldData();
        boolean isBounce = data.getOnBlock() == Material.SLIME_BLOCK || NmsBlockTags.isBed(data.getOnBlock());
        return data.maybeInLiquid() || data.getClimbing().determineOptimistically() ||
                result.getSimulationContext().usesFallFlyingMovement() || isBounce
                || player.checkManager.getKnockbackHandler().isCanTickSkip()
                || player.checkManager.getExplosionHandler().isCanTickSkip();
    }

    public double getOffsetHorizontal(CultPlayer player, PredVector vector, SimulationContext context, PredictionResult lastResult) {
        double normalThreshold = player.getMovementThreshold();
        double scaledThreshold = player.getMovementThreshold();

        if (lastResult != null) {
            double multiplier = context.getTargetScalarHoriz();
            scaledThreshold *= multiplier;
        }

        // TODO: If we want to be TECHNICALLY correct on ALL edge cases, get the frictions and stuff around the player
        Material onBlock = lastResult == null ? Material.STONE : lastResult.getSimulationContext().getWorldData().getOnBlock();

        boolean recoveringFromLastTickSkip = context.getLastTickSkip().getRaw() == 1;

        // If we didn't tick skip after the knockback, it's just the threshold again
        boolean lastKnockback = recoveringFromLastTickSkip && lastResult != null && lastResult.getInitialStartingVel().isKnockback();
        boolean newVectorPointThree = context.getLastTickSkip().hasOccurredSince(0) && vector.isKnockback();

        if (newVectorPointThree || lastKnockback) {
            return scaledThreshold;
        }

        boolean explicit003 = vector.isTickSkip() || context.getLastTickSkip().hasOccurredSince(1);

        // No stupidity to be found here.
        if (!explicit003) return 0;

        float friction = BlockProperties.getMaterialFriction(onBlock);

        // (offset * 2) * 0.6 = max + 0.03 offset
        double pointThree = friction * (normalThreshold * 2) + scaledThreshold;

        // 0.06 * 0.91 = max + 0.03 offset (horizontal friction is lower in the air)
        if (player.lastOnGround || player.isFlying) {
            pointThree = 0.91 * (normalThreshold * 2) + scaledThreshold;
        }

        // Friction while gliding is 0.99 horizontally, very low
        if (context.usesFallFlyingMovement() || (lastResult != null && lastResult.getSimulationContext().usesFallFlyingMovement())) {
            pointThree = (0.99 * (normalThreshold * 2)) + scaledThreshold;
        }

        return pointThree;
    }
}
