package ac.cult.cultac.utils.latency;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.HashSet;
import java.util.Set;

public class CompensatedFireworks extends CultProcessor implements PostPredictionListener {
    // As this is sync to one player, this does not have to be concurrent
    IntList activeFireworks = new IntArrayList();
    IntList fireworksToRemoveNextTick = new IntArrayList();
    int boostsNextTick;

    public CompensatedFireworks(CultPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (predictionComplete.isTeleport()) {
            // SimulationProcessor#handleTeleport applies the teleport to the boosted velocities.
            boostsNextTick = 0;
            return;
        }

        // Remove all the fireworks that were removed in the last tick
        // Remember to remove with an int not an Integer
        activeFireworks.removeAll(fireworksToRemoveNextTick);
        fireworksToRemoveNextTick.clear();

        // FireworkRocketEntity#tick runs after this tick's travel and boosts the player only while it is fall flying.
        SimulationContext context = predictionComplete.getPredictionResult().getSimulationContext();
        boolean gliding = !player.isBedrockMovement()
                && context != null
                && context.isGliding()
                && !context.stopsFallFlyingAfterTravel()
                // TickRateManager#isEntityFrozen keeps the rockets from ticking while the server's ticks are frozen.
                && !(player.packetStateData.serverTicksFrozen
                        && player.packetStateData.serverFrozenTickStepsRemaining == 0);
        boostsNextTick = gliding ? activeFireworks.size() : 0;
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
        boostsNextTick = 0;
    }

    /**
     * The stored velocities a teleport received before this tick finds: each of {@code velocities} after the last
     * tick's boosts with the look {@code xRot}/{@code yRot}, in either trig table.
     */
    public Set<Vec3> boosted(Set<Vec3> velocities, float xRot, float yRot) {
        if (boostsNextTick == 0) return velocities;
        Set<Vec3> boosted = new HashSet<>(velocities);
        for (int fastMath = 0; fastMath < 2; fastMath++) {
            Vector3dm look = ReachUtils.getLook(player, xRot, yRot);
            for (Vec3 velocity : velocities) {
                Vec3 carried = velocity;
                for (int i = 0; i < boostsNextTick; i++) {
                    carried = ElytraTransform.boostFirework(carried, look);
                    boosted.add(carried);
                }
            }
            player.trigHandler.toggleShitMath();
        }
        return boosted;
    }

    /**
     * ClientPacketListener#handleRespawn replaces the LocalPlayer; the rockets keep boosting the old one. The new
     * player copies the old delta movement only with {@code keepsVelocity}.
     */
    public void localPlayerReplaced(boolean keepsVelocity) {
        activeFireworks.clear();
        fireworksToRemoveNextTick.clear();
        if (!keepsVelocity) boostsNextTick = 0;
    }

    /** The rockets that boosted the stored velocity after the last tick's travel. */
    public int getMaxFireworksAppliedPossible() {
        return boostsNextTick;
    }
}
