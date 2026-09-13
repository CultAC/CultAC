package ac.cult.cultac.manager;

import ac.cult.cultac.checks.BedrockSupported;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.LastInstance;

import java.util.ArrayList;
import java.util.List;

@BedrockSupported
public class LastInstanceManager extends Check implements PostPredictionListener {
    private final List<LastInstance> instances = new ArrayList<>();

    public LastInstanceManager(CultPlayer player) {
        super(player);
    }

    public void addInstance(LastInstance instance) {
        instances.add(instance);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        for (LastInstance instance : instances) {
            instance.tick();
        }
    }
}
