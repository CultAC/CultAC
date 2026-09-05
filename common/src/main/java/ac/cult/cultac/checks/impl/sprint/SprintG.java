package ac.cult.cultac.checks.impl.sprint;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;

@CheckData(name = "SprintG", stableKey = "cult.sprint.water", description = "Sprinting while in water", experimental = true)
public class SprintG extends Check implements PostPredictionListener {
    public SprintG(CultPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        // Teleport results carry no simulation context; there is nothing to read.
        if (predictionComplete.isTeleport()) return;

        // Read water state pessimistically from the completed predictions.
        PredictionResult result = predictionComplete.getPredictionResult();
        PredictionResult lastResult = player.checkManager.getSimulationProcessor().getLastPrediction();

        boolean wasTouchingWater = result != null
                && result.getSimulationContext().getWorldData().getInWater().determinePessimistically();
        boolean wasWasTouchingWater = lastResult != null
                && lastResult.getSimulationContext().getWorldData().getInWater().determinePessimistically();

        boolean wasLastPredictionCompleteChecked = lastResult != null && !lastResult.isTeleport() && !lastResult.isExempt();

        boolean isChecked = !predictionComplete.isTeleport() && !predictionComplete.isExempt();

        PacketEntity riding = player.compensatedEntities.getSelf().getRiding();

        if (wasTouchingWater && (wasWasTouchingWater || player.getClientVersion() == ClientVersion.V_1_21_4)
                && !player.wasEyeInWater && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_13)
                && wasLastPredictionCompleteChecked && isChecked
                && !(riding != null && riding.type == EntityTypesCompat.CAMEL)
                && !player.isSwimming) {
            if (player.isSprinting) {
                flagWithSetback();
            } else {
                reward();
            }
        }
    }
}
