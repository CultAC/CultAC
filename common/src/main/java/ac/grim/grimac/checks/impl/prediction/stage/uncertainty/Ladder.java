package ac.grim.grimac.checks.impl.prediction.stage.uncertainty;

import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.nmsutil.FluidFallingAdjustedMovement;
import ac.grim.grimac.network.protocol.ClientVersion;
import net.minecraft.world.phys.Vec3;

public class Ladder implements UncertaintyHandler {
    @Override
    public PredVector handleUncertainty(GrimPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, PredVector start, Vec3 end) {
        // TODO: Stop people in lava from climbing ladders
        if (lastResult == null
                || !lastResult.getSimulationContext().getWorldData().getClimbing().determineOptimistically()
                || lastResult.getSimulationContext().usesFallFlyingMovement()) return start;

        // We MIGHT be on a ladder
        double maximumPossibleY = start.y;
        double minimumPossibleY = start.y;
        // We technically don't check airclimb on pre-1.14, but we don't target those client versions

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

        double bestY = GrimMath.clamp(end.y, minimumPossibleY, maximumPossibleY);
        // MCP-Reborn LivingEntity#travelInWater does not call handleOnClimbable,
        // so water movement can retain flowing-current horizontal speed while
        // still using the climbable vertical boost.
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_14)
                && lastResult.getSimulationContext().getWorldData().getInWater().determinePessimistically()) {
            return start.withY(bestY, "ladder water");
        }

        if (lastResult.getSimulationContext().getWorldData().getClimbing().determineOptimistically()) {
            double clampedX = GrimMath.clamp(start.x, -0.15, 0.15);
            double clampedZ = GrimMath.clamp(start.z, -0.15, 0.15);
            return start.withXYZ(clampedX, bestY, clampedZ, "ladder");
        } else {
            // Clamp to 0.15 if it's beneficial to us
            if (start.x > 0.15 && end.x <= 0.15) start = start.withX(0.15, "ladder");
            if (start.x < -0.15 && end.x >= -0.15) start = start.withX(-0.15, "ladder");
            if (start.z > 0.15 && end.z <= 0.15) start = start.withZ(0.15, "ladder");
            if (start.z < -0.15 && end.z >= -0.15) start = start.withZ(-0.15, "ladder");
            return start;
        }
    }
}
