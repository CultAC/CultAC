package ac.cult.cultac.checks.impl.prediction.runner;

import ac.cult.cultac.manager.player.CheckManager;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.nmsutil.ReusableVelocitySet;

/**
 * One player's prediction scratch, used on that player's thread. Each member belongs to one call site and is never
 * stored, returned or read after that call returns; its own documentation says what the site writes before reading.
 */
public final class PredictionScratch {
    /**
     * ValidMovements#computeAABB's coordinates handed through the position-form handlers; each tail run writes all
     * three before a handler reads them.
     */
    public final double[] extentCoordinates = new double[3];
    /** GeyserVelocity's moved box; all six bounds and the full-block flag are written on entry. */
    public final SimpleCollisionBox geyserBox = new SimpleCollisionBox();
    /**
     * JavaInsideBlockEffects#scan's boxes while it tests whether any block can have an effect; all six bounds and the
     * full-block flag are written on entry.
     */
    public final SimpleCollisionBox insideTo = new SimpleCollisionBox();

    public final SimpleCollisionBox insideFrom = new SimpleCollisionBox();
    /** JavaFallDistance's moved water box; all six bounds and the full-block flag are written on entry. */
    public final SimpleCollisionBox fallWaterBox = new SimpleCollisionBox();
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
