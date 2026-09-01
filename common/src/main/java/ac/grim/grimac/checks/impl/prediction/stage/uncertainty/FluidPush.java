package ac.grim.grimac.checks.impl.prediction.stage.uncertainty;

import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.checks.impl.prediction.stage.world.WorldData;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.nmsutil.EntityTypesCompat;
import net.minecraft.world.phys.Vec3;

public class FluidPush implements UncertaintyHandler {
    private static final double WATER_CURRENT_RADIUS = 0.014D;
    private static final double WATER_ENTRY_RADIUS = 0.028D;

    @Override
    public PredVector handleUncertainty(GrimPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastContext, PredVector start, Vec3 end) {
        double waterRadius = flowingCurrentRadius(context, lastContext);
        double horizontalRadius = waterRadius;
        double verticalRadius = FluidPush.verticalRadius(waterRadius, start, end);
        if (horizontalRadius <= 0.0D && verticalRadius <= 0.0D) {
            return start;
        }

        PredVector adjusted = start;
        if (horizontalRadius > 0.0D) {
            adjusted = UncertaintyHelper.handleCircular(adjusted, end, horizontalRadius);
        }

        return verticalRadius > 0.0D
                ? UncertaintyHelper.handleVertical(adjusted, end, verticalRadius)
                : adjusted;
    }

    private double flowingCurrentRadius(SimulationContext context, PredictionResult lastContext) {
        WorldData curWorldData = context.getWorldData();
        WorldData lastWorldData = lastContext == null ? null : lastContext.getSimulationContext().getWorldData();
        boolean inFlowingLiquid = curWorldData.getInFlowingLiquid().determineOptimistically();
        boolean enteredFlowingLiquid = inFlowingLiquid
                && (lastWorldData == null || !lastWorldData.getInFlowingLiquid().determinePessimistically());
        if (isPigOrStriderWater(context) || enteredFlowingLiquid) {
            return WATER_ENTRY_RADIUS;
        }

        return inFlowingLiquid ? WATER_CURRENT_RADIUS : 0.0D;
    }

    static double verticalRadius(double waterRadius, PredVector start, Vec3 end) {
        return end.y < start.y ? waterRadius : 0.0D;
    }

    private boolean isPigOrStriderRoot(SimulationContext context) {
        PacketEntity vehicle = context.getVehicle();
        return vehicle != null && (vehicle.type == EntityTypesCompat.PIG || vehicle.type == EntityTypesCompat.STRIDER);
    }

    private boolean isPigOrStriderWater(SimulationContext context) {
        return isPigOrStriderRoot(context)
                && (context.getWorldData().getInWater().determineOptimistically()
                || context.getWorldData().getInFlowingLiquid().determineOptimistically());
    }
}
