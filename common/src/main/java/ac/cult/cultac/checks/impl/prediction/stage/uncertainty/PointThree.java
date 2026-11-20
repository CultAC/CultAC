package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.VectorUtils;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.ClientBlockProperties;
import ac.cult.cultac.utils.nmsutil.Friction;
import java.util.List;

public class PointThree implements UncertaintyHandler {

    @Override
    public PredVector handleUncertainty(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            PredVector start,
            Vec3 end) {
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
        boolean onGroundFuckery = player.checkManager
                .getSimulationProcessor()
                .getLastOnGroundSkip()
                .hasOccurredSince(1);

        if (controlsVerticalMovement) {
            result.getValidMovements().setControlsVerticalMovement(true);
        }

        if (canVerticalTickSkip && end.y < start.y) { // we want to go down
            SimpleCollisionBox box = new SimpleCollisionBox(start, start);
            // Maximum amount skipped when falling
            double gravity = player.compensatedEntities.getEntityInControl().gravity;
            if (start.y <= 0.0D && context.getEntities().getSlowFallingAmplifier() != null) {
                gravity = Math.min(gravity, 0.01D);
            }
            double verticalDrag = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2)
                    ? Friction.computeModifiedFriction(
                            0.98F, (float) player.compensatedEntities.getEntityInControl().airDragModifier)
                    : 0.98F;
            // Entity#pushFromExplosion adds its impulse to the delta movement; only the carried part is unknown.
            box.unionY(explosionImpulseY(start) + (-gravity - threshold * 2) * verticalDrag);
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

    @Override
    public void positionInto(
            double[] xyz,
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            PredVector carrier,
            double endX,
            double endY,
            double endZ) {
        double offsetHorizontal = getOffsetHorizontal(player, carrier, context, lastResult);
        UncertaintyHelper.circularInto(xyz, endX, endZ, offsetHorizontal);

        if (carrier.isTickSkip() && player.compensatedEntities.getLevitationAmplifier() != null) {
            double maxY = 0.05 * (player.compensatedEntities.getLevitationAmplifier() + 1) * 0.2;
            if (endY > xyz[1]) {
                UncertaintyHelper.verticalInto(xyz, endY, maxY);
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

        boolean canVerticalTickSkip = carrier.isTickSkip();
        boolean controlsVerticalMovement = canVerticalTickSkip && controlsVerticalMovement(player, result);
        boolean recoveringTickSkip = context.getLastTickSkip().getRaw() == 1;
        boolean onGroundFuckery = player.checkManager
                .getSimulationProcessor()
                .getLastOnGroundSkip()
                .hasOccurredSince(1);

        if (controlsVerticalMovement) {
            result.getValidMovements().setControlsVerticalMovement(true);
        }

        if (canVerticalTickSkip && endY < xyz[1]) {
            double minY = xyz[1];
            double maxY = xyz[1];
            double gravity = player.compensatedEntities.getEntityInControl().gravity;
            if (xyz[1] <= 0.0D && context.getEntities().getSlowFallingAmplifier() != null) {
                gravity = Math.min(gravity, 0.01D);
            }
            double verticalDrag = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2)
                    ? Friction.computeModifiedFriction(
                            0.98F, (float) player.compensatedEntities.getEntityInControl().airDragModifier)
                    : 0.98F;
            double skipped = explosionImpulseY(carrier) + (-gravity - threshold * 2) * verticalDrag;
            if (skipped < minY) minY = skipped;
            if (skipped > maxY) maxY = skipped;
            double x = CultMath.clamp(endX, xyz[0], xyz[0]);
            double y = CultMath.clamp(endY, minY, maxY);
            double z = CultMath.clamp(endZ, xyz[2], xyz[2]);
            xyz[0] = x;
            xyz[1] = y;
            xyz[2] = z;

            if (!valid.isTestingMaxStartingVelExtents(endX, endY, endZ)) {
                result.getValidMovements().setDidTickSkipGravity(true);
            }
        } else if (controlsVerticalMovement) {
            UncertaintyHelper.verticalInto(xyz, endY, threshold);
        } else if (recoveringTickSkip || onGroundFuckery) {
            if (lastControlVertical || lastGravity || onGroundFuckery) {
                UncertaintyHelper.verticalInto(xyz, endY, threshold);
            } else if (endY < xyz[1]) {
                UncertaintyHelper.verticalInto(xyz, endY, threshold);
            }
        }
    }

    private static double explosionImpulseY(PredVector start) {
        double impulseY = 0.0;
        List<TransactionVel> modifiers = start.getPacketModifiers();
        if (modifiers == null) return impulseY;
        for (TransactionVel modifier : modifiers) {
            if (!modifier.isVelocity()) impulseY += modifier.getVel().y;
        }
        return impulseY;
    }

    private boolean controlsVerticalMovement(CultPlayer player, PredictionResult result) {
        WorldData data = result.getSimulationContext().getWorldData();
        boolean isBounce =
                BlockIds.is(data.getOnBlock(), BlockIds.SLIME_BLOCK) || ClientBlockProperties.isBed(data.getOnBlock());
        return data.maybeInLiquid()
                || data.getClimbing().determineOptimistically()
                || result.getSimulationContext().usesFallFlyingMovement()
                || isBounce
                || player.checkManager.getKnockbackHandler().isCanTickSkip()
                || player.checkManager.getExplosionHandler().isCanTickSkip();
    }

    public double getOffsetHorizontal(
            CultPlayer player, PredVector vector, SimulationContext context, PredictionResult lastResult) {
        double normalThreshold = player.getMovementThreshold();
        double scaledThreshold = player.getMovementThreshold();

        if (lastResult != null) {
            double multiplier = context.getTargetScalarHoriz();
            scaledThreshold *= multiplier;
        }

        // TODO: If we want to be TECHNICALLY correct on ALL edge cases, get the frictions and stuff around the player
        int onBlock = lastResult == null
                ? BlockIds.STONE.defaultState()
                : lastResult.getSimulationContext().getWorldData().getOnBlock();

        boolean recoveringFromLastTickSkip = context.getLastTickSkip().getRaw() == 1;

        // If we didn't tick skip after the knockback, it's just the threshold again
        boolean lastKnockback = recoveringFromLastTickSkip
                && lastResult != null
                && lastResult.getInitialStartingVel().isKnockback();
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
        if (context.usesFallFlyingMovement()
                || (lastResult != null && lastResult.getSimulationContext().usesFallFlyingMovement())) {
            pointThree = (0.99 * (normalThreshold * 2)) + scaledThreshold;
        }

        return pointThree;
    }
}
