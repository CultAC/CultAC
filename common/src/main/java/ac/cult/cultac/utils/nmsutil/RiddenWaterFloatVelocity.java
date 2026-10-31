package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RiddenWaterFloatVelocity {
    private RiddenWaterFloatVelocity() {}

    public static Set<Vec3> apply(PredictionResult result, Set<Vec3> velocities) {
        SimulationContext context = result.getSimulationContext();
        if (context.getVehicle() == null || !context.getWorldData().isCouldFloatWhileRidden()) {
            return velocities;
        }

        var adjusted = new ArrayList<>(velocities);
        apply(result, adjusted, 0);
        return new HashSet<>(adjusted);
    }

    /** Only the velocities of one fluid-travel branch reach LivingEntity's floating helper. */
    static void apply(PredictionResult result, List<Vec3> velocities, int from) {
        var context = result.getSimulationContext();
        if (context.getVehicle() == null || !context.getWorldData().isCouldFloatWhileRidden()) return;
        int size = velocities.size();
        for (int i = from; i < size; i++) {
            Vec3 floated = velocities.get(i).add(0, (double) 0.04F, 0);
            if (context.getWorldData().isCanFloatWhileRidden()) velocities.set(i, floated);
            else velocities.add(floated);
        }
    }
}
