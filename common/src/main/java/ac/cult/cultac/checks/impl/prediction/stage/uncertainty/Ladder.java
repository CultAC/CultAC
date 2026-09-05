package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.nmsutil.FluidFallingAdjustedMovement;
import ac.cult.cultac.network.protocol.ClientVersion;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Material;

public class Ladder implements UncertaintyHandler {
    @Override
    public PredVector handleUncertainty(CultPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, PredVector start, Vec3 end) {
        // TODO: Stop people in lava from climbing ladders
        boolean previousClimbBoost = lastResult != null
                && lastResult.getSimulationContext().getWorldData().getClimbing().determineOptimistically()
                && !lastResult.getSimulationContext().usesFallFlyingMovement();

        if (previousClimbBoost) {
            double maximumPossibleY = start.y;
            double minimumPossibleY = start.y;

            // Ladders restrict the player's movement to 0.15 horizontally
            // They set 0.2 to the end of the tick's velocity, before applying gravity
            // 1.14+ players can climb underwater
            // There is an edge case when having slow falling for the first tick
            for (boolean falling : lastResult.getIsFalling().getStates()) {
                double grav = player.compensatedEntities.getEntityInControl().gravity;
                if (falling && lastResult.getSimulationContext().getEntities().getSlowFallingAmplifier() != null)
                    grav = Math.min(grav, 0.01D);
                if (!player.compensatedEntities.getEntityInControl().hasGravity)
                    grav = 0;

                // TODO: Handle unknown fluid correctly
                if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_14) && lastResult.getSimulationContext().getWorldData().getInWater().determinePessimistically()) {
                    Vec3 modified = start.withY(0.2D * 0.8F);
                    modified = FluidFallingAdjustedMovement.getFluidFallingAdjustedMovement(player, grav, falling, modified);
                    maximumPossibleY = Math.max(maximumPossibleY, modified.y);
                    minimumPossibleY = Math.min(minimumPossibleY, modified.y);
                } else if (!lastResult.getSimulationContext().getWorldData().mustBeInLiquid()) {
                    Vec3 modified = start.withY((0.2D - grav) * 0.98F);
                    maximumPossibleY = Math.max(maximumPossibleY, modified.y);
                    minimumPossibleY = Math.min(minimumPossibleY, modified.y);
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
        if (!context.getWorldData().getClimbingAtStart().determineOptimistically()
                || context.usesFallFlyingMovement() || context.getWorldData().mustBeInLiquid()) return start;

        double clampedX = CultMath.clamp(start.x, -0.15F, 0.15F);
        double clampedZ = CultMath.clamp(start.z, -0.15F, 0.15F);
        double clampedY = Math.max(start.y, -0.15F);
        Vec3 from = context.getStart();
        if (clampedY < 0 && context.isSneaking()
                && player.compensatedWorld.getBlockDataAt(from.x, from.y, from.z).getMaterial() != Material.SCAFFOLDING) {
            clampedY = 0;
        }
        if (context.getWorldData().getClimbingAtStart().isDesync() || context.getWorldData().maybeInLiquid()) {
            clampedX = CultMath.clamp(end.x, Math.min(start.x, clampedX), Math.max(start.x, clampedX));
            clampedY = CultMath.clamp(end.y, Math.min(start.y, clampedY), Math.max(start.y, clampedY));
            clampedZ = CultMath.clamp(end.z, Math.min(start.z, clampedZ), Math.max(start.z, clampedZ));
        }
        return start.withXYZ(clampedX, clampedY, clampedZ, "ladder");
    }
}
