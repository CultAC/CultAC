package ac.grim.grimac.checks.impl.prediction;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.event.events.CompletePredictionEvent;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.checks.impl.prediction.checks.psuedo.Speed;
import ac.grim.grimac.checks.impl.prediction.checks.psuedo.Strafe;
import ac.grim.grimac.checks.impl.prediction.checks.psuedo.VehicleOffset;
import ac.grim.grimac.checks.impl.prediction.profile.MovementProfiles;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import lombok.Getter;

//@CheckData(name = "Simulation", configName = "Simulation", decay = 0.02)
public class OffsetHandler extends Check implements PostPredictionListener {
    private static final CompletePredictionEvent.Channel COMPLETE_PREDICTION_CHANNEL =
            GrimAPI.INSTANCE.getEventBus().get(CompletePredictionEvent.class);
    // Config
    double setbackDecayMultiplier;
    double threshold;
    double immediateSetbackThreshold;
    double maxAdvantage;
    double maxCeiling;

    // Current advantage gained
    @Getter double advantageGained = 0;

    public OffsetHandler(GrimPlayer grimPlayer) { super(grimPlayer, CheckInfo.builder()
            .name("Simulation")
            .stableKey("grim.prediction.simulation")
            .description("Moved differently than predicted movement simulation")
            .decay(0.02)
            .build()); }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        PredictionResult predictionResult = predictionComplete.getPredictionResult();
        if (!MovementProfiles.forPlayer(player).usesSharedOffsetSetbacks(player)) return;
        if (!player.shouldEnforceMovementSetbacks()) return;

        // Teleport
        if (predictionResult.isTeleport) return;
        // Exempt or otherwise no flagging
        if (predictionResult.flagSeverity == 0.0) {
            advantageGained *= setbackDecayMultiplier;
            return;
        }

        double offset = setbackOffset(predictionResult);

        // The pinned API artifact exposes CompletePredictionEvent through Grim's event bus
        // rather than as a Bukkit event; fire() returns true when a handler cancelled it.
        if (COMPLETE_PREDICTION_CHANNEL.fire(getPlayer(), this, offset)) return;

        // Short circuit out flag call
        if ((offset >= threshold || offset >= immediateSetbackThreshold) && flag()) {
            advantageGained += offset;

            boolean isSetback = advantageGained >= maxAdvantage || offset >= immediateSetbackThreshold;

            if (isSetback) {
                player.getSetbackTeleportUtil().executeViolationSetback();
            }

            advantageGained = Math.min(advantageGained, maxCeiling);
        } else {
            advantageGained *= setbackDecayMultiplier;
        }
    }

    private double setbackOffset(PredictionResult predictionResult) {
        double offset = predictionResult.getOffset();
        for (PredictionResult.Flag flag : predictionResult.getFlags()) {
            Check check = flag.getCheck();
            if (check instanceof Speed || check instanceof Strafe || check instanceof VehicleOffset) {
                offset = Math.max(offset, flag.getSeverity());
            }
        }
        return offset;
    }

    @Override
    public void reload() {
        super.reload();
        setbackDecayMultiplier = getConfig().getDoubleElse("Simulation.setback-decay-multiplier", 0.999);
        threshold = getConfig().getDoubleElse("Simulation.threshold", 0.001);
        immediateSetbackThreshold = getConfig().getDoubleElse("Simulation.immediate-setback-threshold", 0.1);
        maxAdvantage = getConfig().getDoubleElse("Simulation.max-advantage", 1);
        maxCeiling = getConfig().getDoubleElse("Simulation.max-ceiling", 4);

        if (maxAdvantage == -1) maxAdvantage = Double.MAX_VALUE;
        if (immediateSetbackThreshold == -1) immediateSetbackThreshold = Double.MAX_VALUE;
    }
}
