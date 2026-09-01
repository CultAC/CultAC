package ac.grim.grimac.checks.impl.prediction.checks;

import ac.grim.grimac.bedrock.prediction.integration.BedrockInputAnalyzer;
import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.player.GrimPlayer;
import java.util.Arrays;
import java.util.List;

public class PredCheckRunner extends GrimProcessor {
    private final List<EngineCheck> checks = Arrays.asList(
            new VerticalAnalyzer(),
            new HorizontalAnalyzer(),
            new BedrockInputAnalyzer(),
            new ElytraCheck(),
            new VehicleOffsetCheck(),
            new NoFall(),
            player.knockbackHandler,
            player.explosionHandler
    );

    public PredCheckRunner(GrimPlayer player) {
        super(player);
    }

    public void handleResult(PredictionResult result, PredictionResult lastResult) {
        for (EngineCheck check : checks) {
            check.handleResult(player, result, lastResult);
        }
    }
}
