package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;

public class FluidPush implements UncertaintyHandler {
    private static final double WATER_CURRENT_RADIUS = 0.014D;
    private static final double WATER_ENTRY_RADIUS = 0.028D;

    private final boolean beforeCollision;

    public FluidPush() {
        this(false);
    }

    /**
     * {@code beforeCollision} selects the instance that pushes a Java vehicle, which runs before
     * {@link CollisionModifier}: Entity#updateFluidHeightAndDoFluidPushing adds the current to the velocity that
     * Entity#collide then clips.
     */
    public FluidPush(boolean beforeCollision) {
        this.beforeCollision = beforeCollision;
    }

    @Override
    public boolean skipsContext(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
        return !pushes(context) || flowingCurrentRadius(context, lastResult) <= 0.0D;
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
        if (!pushes(context)) return start;
        double waterRadius = flowingCurrentRadius(context, lastContext);
        double verticalRadius = FluidPush.verticalRadius(waterRadius, start, end);
        if (waterRadius <= 0.0D) {
            return start;
        }

        PredVector adjusted = UncertaintyHelper.handleCircular(start, end, waterRadius);
        return verticalRadius > 0.0D ? UncertaintyHelper.handleVertical(adjusted, end, verticalRadius) : adjusted;
    }

    private boolean pushes(SimulationContext context) {
        return CollisionModifier.isJavaVehicleMove(context) == beforeCollision;
    }

    private double flowingCurrentRadius(SimulationContext context, PredictionResult lastContext) {
        WorldData curWorldData = context.getWorldData();
        WorldData lastWorldData =
                lastContext == null ? null : lastContext.getSimulationContext().getWorldData();
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
        return vehicle != null && (vehicle.type == EntityTypeIds.PIG || vehicle.type == EntityTypeIds.STRIDER);
    }

    private boolean isPigOrStriderWater(SimulationContext context) {
        return isPigOrStriderRoot(context)
                && (context.getWorldData().getInWater().determineOptimistically()
                        || context.getWorldData().getInFlowingLiquid().determineOptimistically());
    }
}
