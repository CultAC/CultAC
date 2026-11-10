package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.player.CultPlayer;
import java.util.function.Supplier;

/** One trig table for a whole movement candidate, never the box between the two tables' results. */
public final class JavaMovementMath extends PredVector {
    private final boolean vanilla;

    public JavaMovementMath(PredVector velocity, boolean vanilla) {
        super(velocity, velocity, vanilla ? "vanilla math" : "fastmath");
        this.vanilla = vanilla;
    }

    public boolean vanilla() {
        return vanilla;
    }

    /** Select this candidate's table until the caller restores the returned table in a finally block. */
    public static boolean select(CultPlayer player, PredVector velocity) {
        var trig = player == null ? null : player.trigHandler;
        boolean previous = trig == null || trig.isVanillaMath();
        JavaMovementMath choice = velocity == null ? null : velocity.firstInLineage(JavaMovementMath.class);
        if (choice != null && trig != null) trig.setVanillaMath(choice.vanilla);
        return previous;
    }

    public static void restore(CultPlayer player, boolean previous) {
        if (player != null && player.trigHandler != null) player.trigHandler.setVanillaMath(previous);
    }

    public static <T> T calculate(CultPlayer player, PredVector velocity, Supplier<T> operation) {
        boolean previous = select(player, velocity);
        try {
            return operation.get();
        } finally {
            restore(player, previous);
        }
    }
}
