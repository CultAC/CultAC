package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.bedrock.prediction.integration.BedrockInputAnalyzer;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.player.CultPlayer;
import java.util.Arrays;
import java.util.List;

public class PredCheckRunner extends CultProcessor {
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

    public PredCheckRunner(CultPlayer player) {
        super(player);
    }

    public void handleResult(PredictionResult result, PredictionResult lastResult) {
        for (EngineCheck check : checks) {
            check.handleResult(player, result, lastResult);
        }
    }
}
