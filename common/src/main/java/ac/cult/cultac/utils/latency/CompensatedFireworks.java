package ac.cult.cultac.utils.latency;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

public class CompensatedFireworks extends CultProcessor implements PostPredictionListener {
    // As this is sync to one player, this does not have to be concurrent
    IntList activeFireworks = new IntArrayList();
    IntList fireworksToRemoveNextTick = new IntArrayList();

    public CompensatedFireworks(CultPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (predictionComplete.isTeleport()) return;

        // Remove all the fireworks that were removed in the last tick
        // Remember to remove with an int not an Integer
        activeFireworks.removeAll(fireworksToRemoveNextTick);
        fireworksToRemoveNextTick.clear();
    }

    public void addNewFirework(int entityID) {
        activeFireworks.add(entityID);
    }

    public void removeFirework(int entityID) {
        fireworksToRemoveNextTick.add(entityID);
    }

    public int getMaxFireworksAppliedPossible() {
        return activeFireworks.size();
    }
}
