package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;

/** ServerboundMoveVehiclePacket reports the active interpolation target instead of the root's physical position. */
public final class JavaVehicleReport {
    private JavaVehicleReport() {}

    public static boolean followsTarget(CultPlayer player, SimulationContext context) {
        var vehicle = context.getVehicle();
        return !player.isBedrockMovement()
                && context.getVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && player.packetStateData.isVehicleMovementFromClientTick()
                && vehicle != null
                && vehicle.newPacketLocation != null
                && vehicle.newPacketLocation.hasActiveInterpolationTarget();
    }

    /** The expected packet delta is separate from the normal engine's accepted physical travel. */
    public static Vec3 expectedMovement(CultPlayer player, PredictionResult result) {
        var context = result.getSimulationContext();
        if (!followsTarget(player, context)) return null;
        return targetPosition(context, JavaMovementEngine.passAfterTravel(result))
                .subtract(context.getStart());
    }

    static Vec3 targetPosition(SimulationContext context, JavaPassAfterTravel pass) {
        var interpolation = pass == null ? null : pass.result().interpolation();
        return (interpolation == null ? context.getVehicle().newPacketLocation : interpolation).targetPosition();
    }
}
