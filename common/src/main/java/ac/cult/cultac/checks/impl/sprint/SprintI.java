package ac.cult.cultac.checks.impl.sprint;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaAiStepSprint.SprintBlock;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.storage.verbose.Verbose;

/**
 * A sprint the client announced that LocalPlayer#aiStep cannot have set: a START_SPRINTING canStartSprinting fails, or
 * an announced sprint the tick's shouldStopRunSprinting or canStartSprinting leaves clear.
 */
@CheckData(
        name = "SprintI",
        stableKey = "cult.sprint.start_conditions",
        description = "Started sprinting without being able to")
public class SprintI extends Check implements PostPredictionListener {
    private static final Verbose START = Verbose.of("start, no forward input").or("start, crouching");
    private static final Verbose TICK = Verbose.of("tick, no forward input").or("tick, moving slowly");

    public SprintI(CultPlayer player) {
        super(player);
    }

    /** A START_SPRINTING that no LocalPlayer#aiStep sends, as {@code blocked} fails canStartSprinting. */
    public void onStartBlocked(SprintBlock blocked) {
        flag(blocked == SprintBlock.NO_FORWARD_INPUT ? START.write(verbose(), 0) : START.write(verbose(), 1));
    }

    /** A sprint announcement the tick's aiStep cannot have left set, as {@code blocked} clears its flag. */
    public void onImpossibleAnnouncement(SprintBlock blocked) {
        flag(blocked == SprintBlock.NO_FORWARD_INPUT ? TICK.write(verbose(), 0) : TICK.write(verbose(), 1));
    }
}
