package ac.cult.cultac.checks.impl.sprint;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import org.jetbrains.annotations.Nullable;

/**
 * A passenger's sprint flag. From 1.21.4 LocalPlayer#aiStep stops the sprint of a passenger whose vehicle cannot sprint
 * (LocalPlayer#shouldStopSprinting in 1.21.4, LocalPlayer#isSprintingPossible in 26.3), and LocalPlayer#tick in 1.21.4
 * and LocalPlayer#sendChanges in 26.3 send that tick's STOP_SPRINTING after the vehicle move. A ridden move therefore
 * carries the flag the previous tick sent.
 */
final class PassengerSprint {
    private PassengerSprint() {}

    /**
     * Whether vanilla holds no sprint on this ridden move: the vehicle cannot sprint and the previous move was ridden on
     * it or on another vehicle that cannot sprint, so the flag the client sent after that move is already the stopped
     * one. Only a camel or camel husk lets its controlling passenger keep the sprint, so a previous move on one may
     * still carry it.
     */
    static boolean stopped(CultPlayer player, PredictionResult result, @Nullable PredictionResult lastResult) {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_4)) return false;
        PacketEntity riding = player.compensatedEntities.getSelf().getRiding();
        PacketEntity vehicle = result.getSimulationContext().getVehicle();
        PacketEntity lastVehicle = lastResult == null ? null : lastResult.getSimulationContext().getVehicle();
        return riding != null
                && vehicle != null
                && !player.compensatedEntities.vehicles.vehicleCanSprint(riding)
                && lastVehicle != null
                && (lastVehicle == vehicle || !EntityTypeUtil.isCamelFamily(lastVehicle.type));
    }
}
