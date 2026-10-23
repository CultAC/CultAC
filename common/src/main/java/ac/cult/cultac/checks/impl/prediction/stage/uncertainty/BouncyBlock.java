package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaInsideBlockEffects;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.VectorUtils;
import ac.cult.cultac.utils.nmsutil.ClientBlockProperties;

public class BouncyBlock implements UncertaintyHandler {
    @Override
    public boolean skipsContext(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
        if (lastResult == null) return true;
        int onBlock = lastResult.getSimulationContext().getWorldData().getOnBlock();
        return !BlockIds.is(onBlock, BlockIds.SLIME_BLOCK)
                && (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2)
                        || !ClientBlockProperties.isBed(onBlock));
    }

    @Override
    public PredVector handleUncertainty(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastContext,
            PredVector start,
            Vec3 end) {
        if (lastContext == null || start.isKnockback()) return start;

        int onBlock = lastContext.getSimulationContext().getWorldData().getOnBlock();

        // SlimeBlock#stepOn scales the carried horizontal velocity by 0.4 to 0.42 below 0.1 vertical speed. A Java
        // client's or living vehicle's exact-effects carry already holds that scale (JavaInsideBlockEffects#stepOn).
        if (BlockIds.is(onBlock, BlockIds.SLIME_BLOCK)
                && !JavaInsideBlockEffects.carriesStepOn(player, lastContext.getSimulationContext())) {
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

        // 0.66F for beds, times this multiplier again specified above.
        double bounceMultiplier =
                player.compensatedEntities.getEntityInControl().isLivingEntity() ? 1.0 : 0.8;

        // The player can bounce this high
        double bounceAmount = minYAmount * -1 * bounceMultiplier * bounceLevel;
        // Entity#move stops a descending velocity at the floor, so a client that moved down less than the carried
        // velocity, or not at all, moved as the carried velocity does and Entity#move registers the collision below.
        if (end.y <= 0.0 && start.y < end.y) return start;
        double bounceY = CultMath.clamp(end.y, start.y, bounceAmount);
        return start.withY(bounceY, "bounce");
    }

    private float getBouncyBlockLevel(int onBlock) {
        if (BlockIds.is(onBlock, BlockIds.SLIME_BLOCK)) {
            return 1;
        } else if (ClientBlockProperties.isBed(onBlock)) {
            return 0.66F;
        }
        return 0;
    }
}
