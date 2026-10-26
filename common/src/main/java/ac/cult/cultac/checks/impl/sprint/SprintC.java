package ac.cult.cultac.checks.impl.sprint;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.DeadCheck;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;

@CheckData(
        name = "SprintC",
        stableKey = "cult.sprint.using_item",
        description = "Sprinting while using an item",
        setback = 5,
        experimental = true)
@DeadCheck(reason = DeadCheck.Reason.VERSION_GATED, detail = "Returns for every client except 1.21.4.")
public class SprintC extends Check implements PostPredictionListener {
    private boolean flaggedLastTick = false;
    // A sprint the client started during this use, which no later tick of the use carries legitimately.
    private boolean sprintStartedWhileUsing = false;

    public SprintC(CultPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        // Teleport results carry no simulation context; there is nothing to read.
        if (predictionComplete.isTeleport()) return;

        if (player.isClientUsingItem()) {
            ClientVersion version = player.getClientVersion();

            // https://bugs.mojang.com/browse/MC-152728
            if (version != ClientVersion.V_1_21_4) {
                return;
            }

            PredictionResult result = predictionComplete.getPredictionResult();
            if (result == null) {
                flaggedLastTick = false;
                return;
            }

            if (!player.isSprinting) {
                sprintStartedWhileUsing = false;
                reward();
                flaggedLastTick = false;
                return;
            }

            // LocalPlayer#canStartSprinting starts no sprint while using an item; the flag aiStep began with is the
            // last one sent or a server flag ClientPacketListener#handleSetEntityData applied since.
            if (!player.sprintState.flagBeforeAiStep()) sprintStartedWhileUsing = true;
            // LocalPlayer#shouldStopSprinting's item-use term spares a passenger and a player whose eye was under water.
            SimulationContext context = result.getSimulationContext();
            if (!sprintStartedWhileUsing
                    && (context.getVehicle() != null || context.getWorldData().isUnderwater())) {
                flaggedLastTick = false;
                return;
            }

            if (flaggedLastTick) flagWithSetback();
            flaggedLastTick = true;
        } else {
            sprintStartedWhileUsing = false;
        }
    }
}
