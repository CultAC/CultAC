package ac.cult.cultac.checks.impl.sprint;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.prediction.ClientHorizontalCollision;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;

@CheckData(
        name = "SprintE",
        stableKey = "cult.sprint.wall",
        description = "Sprinting while colliding with a wall",
        setback = 5,
        experimental = true)
public final class SprintE extends Check implements PostPredictionListener {
    private boolean startedSprintingThisTick;
    private boolean wasHardHorizontalCollision;
    private boolean previousPredictionChecked;

    public SprintE(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        if (packet.action() == PlayerCommandAction.START_SPRINTING) {
            startedSprintingThisTick = true;
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        // A pass replayed since the last travel (a block entity after the report before 26.3, a shulker ticking before
        // the player) ran Entity#move after it: its flags are the ones LocalPlayer#aiStep reads. One that left no
        // certain hard collision clears the travel's; the travel's own must be hard too.
        var afterPass = player.checkManager.getSimulationProcessor().takeHardCollisionAfterPass();
        if (afterPass != null && afterPass != DesyncStatus.TRUE) wasHardHorizontalCollision = false;
        if (complete.isTeleport()) {
            wasHardHorizontalCollision = false;
            previousPredictionChecked = false;
            startedSprintingThisTick = false;
            return;
        }

        PredictionResult result = complete.getPredictionResult();
        // A move the prediction did not explain leaves its collision unknown.
        boolean checked = !complete.isExempt() && result != null && result.getFlagSeverity() == 0.0;
        boolean inWater = result != null
                && result.getSimulationContext().getWorldData().isInWater();

        if (checked
                && previousPredictionChecked
                && wasHardHorizontalCollision
                && !startedSprintingThisTick
                && !player.inVehicle()
                && !inWater) {
            if (player.isSprinting) {
                flagWithSetback();
            } else {
                reward();
            }
        }

        // Only a hard collision every candidate agrees on stops the sprint for certain.
        wasHardHorizontalCollision =
                checked && ClientHorizontalCollision.hardForEveryReality(result).determinePessimistically();
        previousPredictionChecked = checked;
        startedSprintingThisTick = false;
    }
}
