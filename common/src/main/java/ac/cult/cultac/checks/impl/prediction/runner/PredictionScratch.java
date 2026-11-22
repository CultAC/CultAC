package ac.cult.cultac.checks.impl.prediction.runner;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHandler;
import ac.cult.cultac.manager.player.CheckManager;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.nmsutil.ReusableVelocitySet;

/**
 * One player's prediction scratch, used on that player's thread. Each member belongs to one call site and is never
 * stored, returned or read after that call returns; its own documentation says what the site writes before reading.
 */
public final class PredictionScratch {
    /** GeyserVelocity's moved box; all six bounds and the full-block flag are written on entry. */
    public final SimpleCollisionBox geyserBox = new SimpleCollisionBox();
    /** WaterCurrent's fluid interaction box; all six bounds and the full-block flag are written on entry. */
    public final SimpleCollisionBox fluidBox = new SimpleCollisionBox();
    /**
     * WorldStageBuilder#recountCollidingTickingAfterPlayer's sweep, its scratch, the contact box and the entity boxes,
     * written as the builder's own boxes for the same call are.
     */
    public final SimpleCollisionBox recountSweep = new SimpleCollisionBox();

    public final SimpleCollisionBox recountSweepScratch = new SimpleCollisionBox();
    public final SimpleCollisionBox recountContact = new SimpleCollisionBox();
    public final SimpleCollisionBox recountEntityBounds = new SimpleCollisionBox();
    public final SimpleCollisionBox recountEntityScratch = new SimpleCollisionBox();

    private ReusableVelocitySet filteredVelocities = new ReusableVelocitySet();

    private boolean keepsHandlers;
    private SimulationContext keptContext;
    private PredictionResult keptLast;
    private UncertaintyHandler[] kept;
    private int keptCount;

    /**
     * ValidMovements#computeAABB's pipeline handlers whose skipsContext is false for {@code context} and
     * {@code lastResult}, in order and {@link #keptHandlerCount} long, or null when this holds none for them. Unlike
     * the other members it outlives its call, from {@link #openKeptHandlers} to {@link #closeKeptHandlers} around one
     * prediction: every skip predicate reads only the context, the last result and player state that the prediction
     * does not write, so the candidates of one context share the handlers. Callers only read the array.
     */
    public UncertaintyHandler[] keptHandlers(SimulationContext context, PredictionResult lastResult) {
        return kept != null && context == keptContext && lastResult == keptLast ? kept : null;
    }

    public int keptHandlerCount() {
        return keptCount;
    }

    public void keepHandlers(
            SimulationContext context, PredictionResult lastResult, UncertaintyHandler[] kept, int count) {
        if (!keepsHandlers) return;
        this.keptContext = context;
        this.keptLast = lastResult;
        this.kept = kept;
        this.keptCount = count;
    }

    public void openKeptHandlers() {
        closeKeptHandlers();
        keepsHandlers = true;
    }

    public void closeKeptHandlers() {
        keepsHandlers = false;
        keptContext = null;
        keptLast = null;
        kept = null;
    }

    /**
     * JavaMovementEngine#applyModifiers' carried velocities: a set that is empty between calls, since that call clears
     * it before returning, and that iterates as a fresh {@code HashSet} filled the same way. A set whose table has
     * grown past the default size would iterate differently, so it is replaced by a new one here.
     */
    public ReusableVelocitySet filteredVelocities() {
        if (!this.filteredVelocities.reusable()) this.filteredVelocities = new ReusableVelocitySet();
        return this.filteredVelocities;
    }

    /** {@code player}'s scratch, or null when it has no simulation processor. */
    public static PredictionScratch of(CultPlayer player) {
        if (player == null) return null;
        CheckManager checks = player.checkManager;
        if (checks == null) return null;
        SimulationProcessor processor = checks.getSimulationProcessor();
        return processor == null ? null : processor.scratch();
    }
}
