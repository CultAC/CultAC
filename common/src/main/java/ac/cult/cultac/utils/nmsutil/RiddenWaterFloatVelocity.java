package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
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
        // The water branch keeps Entity#baseTick's heights: its Entity#move does not refresh them.
        apply(null, result, adjusted, 0, false);
        return new HashSet<>(adjusted);
    }

    /** Only the velocities of one fluid-travel branch reach LivingEntity's floating helper. */
    static void apply(CultPlayer player, PredictionResult result, List<Vec3> velocities, int from, boolean lavaTravel) {
        var context = result.getSimulationContext();
        PacketEntity vehicle = context.getVehicle();
        if (vehicle == null) return;
        boolean could = context.getWorldData().isCouldFloatWhileRidden();
        boolean can = context.getWorldData().isCanFloatWhileRidden();
        // 26.3 also floats after travelInLava. Entity#baseTick saw no water there, so the travel's Entity#move ran
        // LivingEntity#checkFallDamage's refresh, and the helper reads the heights at the post-move box.
        if (lavaTravel && JavaLivingMoveFluids.refreshes(context.getVersion(), vehicle, false, true))
            could = can =
                    ClientFluidQueries.floatWhileRiddenAt(player, context, VelocityCandidates.postMoveFluidBox(result));
        if (!could) return;
        int size = velocities.size();
        for (int i = from; i < size; i++) {
            Vec3 floated = velocities.get(i).add(0, (double) 0.04F, 0);
            if (can) velocities.set(i, floated);
            else velocities.add(floated);
        }
    }
}
