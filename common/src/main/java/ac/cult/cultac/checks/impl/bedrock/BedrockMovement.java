package ac.cult.cultac.checks.impl.bedrock;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.checks.BedrockSupported;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import java.util.Locale;
import org.bukkit.ChatColor;

@BedrockSupported
public final class BedrockMovement extends Check implements PostPredictionListener {
    private static final double DEFAULT_SETBACK_DECAY_MULTIPLIER = 0.999D;
    private static final double DEFAULT_IMMEDIATE_FLAG_THRESHOLD = 0.1D;
    private static final double DEFAULT_MAX_ADVANTAGE = 1.0D;
    private static final double DEFAULT_MAX_CEILING = 4.0D;

    private double setbackDecayMultiplier = DEFAULT_SETBACK_DECAY_MULTIPLIER;
    private double immediateFlagThreshold = DEFAULT_IMMEDIATE_FLAG_THRESHOLD;
    private double maxAdvantage = DEFAULT_MAX_ADVANTAGE;
    private double maxCeiling = DEFAULT_MAX_CEILING;
    private double advantageGained;

    public BedrockMovement(CultPlayer player) {
        super(player, CheckInfo.builder()
                .name("BedrockMovement")
                .stableKey("cult.bedrock.movement")
                .description("Validates Bedrock server-authoritative movement with CultAC Bedrock prediction")
                .build());
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (player.movementPlatform != MovementPlatform.BEDROCK
                || player.bedrockState == null
                || predictionComplete.getPredictionResult() == null
        ) {
            return;
        }
        PredictionResult predictionResult = predictionComplete.getPredictionResult();
        if (predictionComplete.isTeleport() || predictionComplete.isExempt()) {
            return;
        }
        SimulationContext simulationContext = predictionResult.getSimulationContext();
        if (simulationContext == null || !simulationContext.hasTrustedAuthoredInput()) {
            // Bookkeeping passes do not contain a movement request.
            return;
        }

        // Client ticks identify payload order, not duplicate predictions.
        PredictionResult.Flag endpointFlag = predictionResult.getFlag(BedrockMovement.class);
        if (endpointFlag != null) {
            double offset = endpointFlag.getSeverity();
            double threshold = CultAPI.INSTANCE.getConfigManager().getBedrockMovementPositionFlagThreshold();
            String verbose = formatVerbose(
                    offset,
                    predictionResult.getIdentifier());
            // Test the immediate offset before the accumulated-advantage cap.
            if (shouldFlag(offset, threshold, immediateFlagThreshold) && flag(verbose)) {
                advantageGained += offset;
                if (shouldSetback(advantageGained, offset, maxAdvantage, immediateFlagThreshold)) {
                    player.getSetbackTeleportUtil().executeViolationSetback();
                }
                advantageGained = Math.min(advantageGained, maxCeiling);
            } else {
                advantageGained *= setbackDecayMultiplier;
            }
        } else {
            BedrockPredictionResult bedrockResult = predictionResult.getProfileResult(BedrockPredictionResult.class);
            if (bedrockResult == null || bedrockResult.movementResult() == null) {
                return;
            }
            advantageGained *= setbackDecayMultiplier;
            reward();
        }
    }

    @Override
    public void reload() {
        super.reload();
        setbackDecayMultiplier = getConfig().getDoubleElse("Simulation.setback-decay-multiplier", DEFAULT_SETBACK_DECAY_MULTIPLIER);
        immediateFlagThreshold = disabledToMax(
                getConfig().getDoubleElse("Simulation.immediate-setback-threshold", DEFAULT_IMMEDIATE_FLAG_THRESHOLD));
        maxAdvantage = disabledToMax(getConfig().getDoubleElse("Simulation.max-advantage", DEFAULT_MAX_ADVANTAGE));
        maxCeiling = getConfig().getDoubleElse("Simulation.max-ceiling", DEFAULT_MAX_CEILING);
    }

    public boolean shouldEvaluateOffset(double offset, double positionThreshold) {
        return shouldFlag(offset, positionThreshold, immediateFlagThreshold);
    }

    static double disabledToMax(double value) {
        return value == -1.0D ? Double.MAX_VALUE : value;
    }

    static boolean shouldFlag(double offset, double threshold, double immediateSetbackThreshold) {
        return offset >= threshold || offset >= immediateSetbackThreshold;
    }

    static boolean shouldSetback(
            double advantageGained,
            double offset,
            double maxAdvantage,
            double immediateSetbackThreshold
    ) {
        return advantageGained >= maxAdvantage || offset >= immediateSetbackThreshold;
    }

    static String formatVerbose(
            double offset,
            int debugIdentifier
    ) {
        return String.format(Locale.ROOT,
                "%.5f",
                offset)
                + " " + ChatColor.DARK_GRAY + debugIdentifier;
    }

}
