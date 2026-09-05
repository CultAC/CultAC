package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.BubbleColumnData;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.math.CultMath;
import net.minecraft.world.phys.Vec3;

public class BubbleColumn implements UncertaintyHandler {
    @Override
    public PredVector handleUncertainty(CultPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, PredVector start, Vec3 end) {
        if (context.getVehicle() != null && !player.isBedrockMovement() && ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine.contextUsesExactEffects(context)) return start;
        if (beforeElytra(context)) return start;
        return apply(context, lastResult, start, end.y);
    }

    static boolean beforeElytra(SimulationContext context) {
        return context.getVehicle() == null && context.usesFallFlyingMovement()
                && !context.getWorldData().mustBeInLiquid()
                && context.getVersion().isNewerThanOrEquals(ac.cult.cultac.network.protocol.ClientVersion.V_26_1);
    }

    static PredVector apply(SimulationContext context, PredictionResult lastResult, PredVector start, double targetY) {
        if (lastResult == null) return start;

        WorldData lastWorldData = lastResult.getSimulationContext().getWorldData();
        BubbleColumnData bubbleColumnData = lastWorldData.getBubbleColumn();
        if (!bubbleColumnData.hasBubbleColumn()) return start;

        double increaseAirUp = bubbleColumnData.getUpAir() * 0.1;
        double increaseUp = bubbleColumnData.getUp() * 0.06;
        double decreaseAirDown = bubbleColumnData.getDownAir() * -0.03;
        double decreaseDown = bubbleColumnData.getDown() * -0.03;

        // Vehicles are weird... true to the original code though
        if (context.getVehicle() instanceof PacketEntityHorse || context.getVehicle() instanceof PacketEntityStrider
                || (context.getVehicle() != null && ac.cult.cultac.utils.nmsutil.EntityTypeUtil.isBoat(context.getVehicle().type))) {
            increaseAirUp *= 2;
            increaseUp *= 2;
            decreaseAirDown *= 2;
            decreaseDown *= 2;
        }

        double bestY = start.y;

        // Downwards velocity can be applied when in an upwards bubble column above 0.7 blocks/tick
        if ((increaseUp != 0 || increaseAirUp != 0) && start.y > 0.7 * 0.8) {
            bestY = CultMath.clamp(targetY, bestY, 0.7 * 0.8);
        }
        // Upwards velocity can be applied when in a downwards bubble column below -0.3 blocks/tick
        if ((decreaseDown != 0 || decreaseAirDown != 0) && start.y < -0.3 * 0.8) {
            bestY = CultMath.clamp(targetY, bestY, -0.3 * 0.8);
        }
        // Upwards velocity is applied when in an upwards bubble column up to a certain limit
        double totalUp = Math.min(start.y + increaseUp + increaseAirUp, increaseAirUp > 0 ? 1.8 : 0.7);
        bestY = CultMath.clamp(targetY, bestY, totalUp);
        // Downwards velocity is applied when in a downwards bubble column up to a certain limit
        double totalDown = Math.max(start.y + decreaseDown + decreaseAirDown, decreaseAirDown < 0 ? -0.9 : -0.3);
        bestY = CultMath.clamp(targetY, bestY, totalDown);

        return start.withY(bestY, "bubble column");
    }
}
