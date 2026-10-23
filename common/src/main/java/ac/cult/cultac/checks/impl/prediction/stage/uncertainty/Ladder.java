package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.runner.ExplosionHandler;
import ac.cult.cultac.checks.impl.prediction.runner.KnockbackHandler;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.FluidFallingAdjustedMovement;

public class Ladder implements UncertaintyHandler {
    @Override
    public boolean skipsContext(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
        boolean previousClimbBoost = lastResult != null
                && lastResult
                        .getSimulationContext()
                        .getWorldData()
                        .getClimbing()
                        .determineOptimistically()
                && !lastResult.getSimulationContext().usesFallFlyingMovement();
        return !previousClimbBoost
                && (!context.getWorldData().getClimbingAtStart().determineOptimistically()
                        || context.usesFallFlyingMovement()
                        || context.getWorldData().mustBeInLiquid());
    }

    @Override
    public PredVector handleUncertainty(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            PredVector start,
            Vec3 end) {
        // TODO: Stop people in lava from climbing ladders
        boolean previousClimbBoost = lastResult != null
                && lastResult
                        .getSimulationContext()
                        .getWorldData()
                        .getClimbing()
                        .determineOptimistically()
                && !lastResult.getSimulationContext().usesFallFlyingMovement();

        if (previousClimbBoost) {
            double maximumPossibleY = start.y;
            double minimumPossibleY = start.y;

            // Ladders restrict the player's movement to 0.15 horizontally
            // They set 0.2 to the end of the tick's velocity, before applying gravity
            // There is an edge case when having slow falling for the first tick
            for (boolean falling : lastResult.getIsFalling().getStates()) {
                double grav = player.compensatedEntities.getEntityInControl().gravity;
                if (falling && lastResult.getSimulationContext().getEntities().getSlowFallingAmplifier() != null)
                    grav = Math.min(grav, 0.01D);
                if (!player.compensatedEntities.getEntityInControl().hasGravity) grav = 0;

                // TODO: Handle unknown fluid correctly
                if (lastResult
                        .getSimulationContext()
                        .getWorldData()
                        .getInWater()
                        .determinePessimistically()) {
                    Vec3 modified = start.withY(0.2D * 0.8F);
                    modified = FluidFallingAdjustedMovement.getFluidFallingAdjustedMovement(
                            player, grav, falling, modified);
                    double carried = afterPacketsThisTick(player, start, modified.y);
                    maximumPossibleY = Math.max(maximumPossibleY, carried);
                    minimumPossibleY = Math.min(minimumPossibleY, carried);
                } else if (!lastResult.getSimulationContext().getWorldData().mustBeInLiquid()) {
                    double carried = afterPacketsThisTick(player, start, (0.2D - grav) * 0.98F);
                    maximumPossibleY = Math.max(maximumPossibleY, carried);
                    minimumPossibleY = Math.min(minimumPossibleY, carried);
                }
            }

            double bestY = CultMath.clamp(end.y, minimumPossibleY, maximumPossibleY);
            // MCP-Reborn LivingEntity#travelInWater does not call handleOnClimbable,
            // so water movement can retain flowing-current horizontal speed while
            // still using the climbable vertical boost.
            start = start.withY(bestY, "ladder carry");
        }

        // handleOnClimbable is a current air-travel operation. In particular,
        // the first ladder tick after gliding clamps even though the previous
        // elytra tick could not have produced a post-move ladder boost.
        DesyncStatus clamps = clampsDeltaMovement(context);
        if (clamps == DesyncStatus.FALSE) return start;

        Vec3 clamped = handleOnClimbable(player, context, start);
        double clampedX = clamped.x;
        double clampedY = clamped.y;
        double clampedZ = clamped.z;
        if (clamps.isDesync()) {
            clampedX = CultMath.clamp(end.x, Math.min(start.x, clampedX), Math.max(start.x, clampedX));
            clampedY = CultMath.clamp(end.y, Math.min(start.y, clampedY), Math.max(start.y, clampedY));
            clampedZ = CultMath.clamp(end.z, Math.min(start.z, clampedZ), Math.max(start.z, clampedZ));
        }
        return start.withXYZ(clampedX, clampedY, clampedZ, "ladder");
    }

    /**
     * The y of {@code start} when the last tick left {@code carriedY}: ClientPacketListener handles this tick's
     * set_entity_motion, which replaces deltaMovement, and explode, which adds to it, in order after that tick.
     */
    private static double afterPacketsThisTick(CultPlayer player, PredVector start, double carriedY) {
        KnockbackHandler knockback = player.checkManager.getKnockbackHandler();
        ExplosionHandler explosion = player.checkManager.getExplosionHandler();
        for (TransactionVel packet : start.getPacketModifiers()) {
            if (packet != knockback.firstBread
                    && packet != knockback.secondBread
                    && packet != explosion.firstBread
                    && packet != explosion.secondBread) continue;
            carriedY = packet.isVelocity() ? packet.getVel().y : carriedY + packet.getVel().y;
        }
        return carriedY;
    }

    /**
     * Whether the travel of {@code context} runs LivingEntity#handleOnClimbable on a climbable start:
     * LivingEntity#travelInAir does, LivingEntity#travelInFluid and the LivingEntity#travelFallFlying glide do not.
     */
    public static DesyncStatus clampsDeltaMovement(SimulationContext context) {
        WorldData world = context.getWorldData();
        if (!world.getClimbingAtStart().determineOptimistically()
                || context.usesFallFlyingMovement()
                || world.mustBeInLiquid()) return DesyncStatus.FALSE;
        return world.getClimbingAtStart().isDesync() || world.maybeInLiquid()
                ? DesyncStatus.UNKNOWN
                : DesyncStatus.TRUE;
    }

    /** LivingEntity#handleOnClimbable's deltaMovement for {@code delta} on a climbable at the start of {@code context}. */
    public static Vec3 handleOnClimbable(CultPlayer player, SimulationContext context, Vec3 delta) {
        double clampedX = CultMath.clamp(delta.x, -0.15F, 0.15F);
        double clampedZ = CultMath.clamp(delta.z, -0.15F, 0.15F);
        double clampedY = Math.max(delta.y, -0.15F);
        Vec3 from = context.getStart();
        if (clampedY < 0
                && context.isSneaking()
                && !BlockIds.is(
                        player.compensatedWorld.getBlockStateIdAt(BlockPos.containing(from.x, from.y, from.z)),
                        BlockIds.SCAFFOLDING)) {
            clampedY = 0;
        }
        return new Vec3(clampedX, clampedY, clampedZ);
    }
}
