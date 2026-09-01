package ac.grim.grimac.checks.impl.sprint;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.nmsutil.EntityTypesCompat;

@CheckData(name = "SprintG", stableKey = "grim.sprint.water", description = "Sprinting while in water", experimental = true)
public class SprintG extends Check implements PostPredictionListener {
    public SprintG(GrimPlayer player) {
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
