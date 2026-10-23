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
        if (!activeFireworks.contains(entityID)) activeFireworks.add(entityID);
    }

    public void removeFirework(int entityID) {
        if (activeFireworks.contains(entityID) && !fireworksToRemoveNextTick.contains(entityID))
            fireworksToRemoveNextTick.add(entityID);
    }

    /** A replacement client level holds none of the old level's rockets. */
    public void clearLevel() {
        activeFireworks.clear();
        fireworksToRemoveNextTick.clear();
    }

    public int getMaxFireworksAppliedPossible() {
        return activeFireworks.size();
    }
}
