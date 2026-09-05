package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.CultAPI;
import ac.grim.grimac.api.event.events.CompletePredictionEvent;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.Speed;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.Strafe;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.VehicleOffset;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import lombok.Getter;
import org.bukkit.ChatColor;

//@CheckData(name = "Simulation", configName = "Simulation", decay = 0.02)
public class OffsetHandler extends Check implements PostPredictionListener {
    private static final Verbose V = Verbose.of("{offset}");
    private static final CompletePredictionEvent.Channel COMPLETE_PREDICTION_CHANNEL =
            CultAPI.INSTANCE.getEventBus().get(CompletePredictionEvent.class);
    // Config
    double setbackDecayMultiplier;
    double threshold;
    double immediateSetbackThreshold;
    double maxAdvantage;
    double maxCeiling;

    // Current advantage gained
    @Getter double advantageGained = 0;

    public OffsetHandler(CultPlayer cultPlayer) { super(cultPlayer, CheckInfo.builder()
            .name("Simulation")
            .stableKey("cult.prediction.simulation")
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

        // The pinned API artifact exposes CompletePredictionEvent through Cult's event bus
        // rather than as a Bukkit event; fire() returns true when a handler cancelled it.
        if (COMPLETE_PREDICTION_CHANNEL.fire(getPlayer(), this, offset)) return;

        // Short circuit out flag call
        if ((offset >= threshold || offset >= immediateSetbackThreshold)
                && flag(V.write(verbose()).f64(offset),
                () -> formatOffset(offset) + " " + ChatColor.DARK_GRAY + predictionResult.getIdentifier())) {
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
