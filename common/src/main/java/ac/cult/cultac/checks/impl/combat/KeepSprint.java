package ac.cult.cultac.checks.impl.combat;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.EngineCheck;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.anticheat.NumFormatter;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;

/**
 * Player#attack's causeExtraKnockback multiplies X/Z movement by 0.6 before the client's travel. Simulation accepts
 * candidates without that damping, like it accepts ignored velocity; this check owns the offset of the best candidate
 * carrying every damping PacketPlayerAttack proved the client applied.
 */
public class KeepSprint extends Check implements EngineCheck, PostPredictionListener {
    private double offsetToFlag;
    private double bestRequiredOffset = Double.MAX_VALUE;

    public static final CheckInfo INFO = CheckInfo.builder()
            .name("KeepSprint")
            .stableKey("cult.combat.keep_sprint")
            .description("Did not take the attack slowdown")
            .setback(10)
            .decay(0.025)
            .build();

    public KeepSprint(CultPlayer player) {
        super(player, INFO);
    }

    @Override
    public void reload() {
        super.reload();
        offsetToFlag = getConfig().getDoubleElse(getConfigName() + ".threshold", 0.001);
    }

    @Override
    public void handleResult(CultPlayer player, PredictionResult result, PredictionResult lastResult) {
        int required = result.getSimulationContext().getMinAttackSlow();
        if (required == 0 || result.getSimulationContext().isTestingPointThree()) return;
        if (result.getInitialStartingVel().getAttackSlows() >= required) {
            bestRequiredOffset = Math.min(bestRequiredOffset, result.getFlagSeverity());
        }
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        double offset = bestRequiredOffset;
        bestRequiredOffset = Double.MAX_VALUE;
        // No candidate carried the damping (no travel path applies it), so there is nothing to compare.
        if (offset == Double.MAX_VALUE || predictionComplete.isTeleport()) return;
        if (offset > offsetToFlag
                && !player.compensatedEntities.getSelf().isDead
                && player.gamemode != GameMode.SPECTATOR) {
            flag("offset=" + NumFormatter.formatNumberStandard(offset));
            setbackIfAboveSetbackVL();
        }
    }
}
