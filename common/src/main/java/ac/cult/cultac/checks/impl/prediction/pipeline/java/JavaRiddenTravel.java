package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;

/** Whether LivingEntity#travelRidden reaches travel, independently of root authority and tickRidden. */
public final class JavaRiddenTravel {
    private JavaRiddenTravel() {}

    public static boolean clearsWithoutTravel(SimulationContext context) {
        var vehicle = context.getVehicle();
        // Only 1.21.5 Mob#canSimulateMovement also requires !isNoAi. travelRidden still ticks steering,
        // then clears deltaMovement without Entity#move, drag, gravity or the virtual fall callback.
        return !context.isBedrockMovement()
                && context.getVersion() == ClientVersion.V_1_21_5
                && vehicle != null
                && vehicle.isLivingEntity()
                && vehicle.actionAlive
                && vehicle.noAI;
    }
}
