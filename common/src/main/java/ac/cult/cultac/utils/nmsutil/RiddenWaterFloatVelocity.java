package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

public final class RiddenWaterFloatVelocity {
    private RiddenWaterFloatVelocity() {
    }

    public static Set<Vec3> apply(PredictionResult result, Set<Vec3> velocities) {
        SimulationContext context = result.getSimulationContext();
        if (context == null
                || context.getVehicle() == null
                || !context.getWorldData().isCouldFloatWhileRidden()) {
            return velocities;
        }

        Set<Vec3> adjusted = new HashSet<>(velocities);
        for (Vec3 velocity : velocities) {
            adjusted.add(velocity.add(0.0D, 0.04D, 0.0D));
        }
        return adjusted;
    }
}
