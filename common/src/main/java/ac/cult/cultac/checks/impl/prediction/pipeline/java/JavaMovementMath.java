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

    public static <T> T calculate(CultPlayer player, PredVector velocity, Supplier<T> operation) {
        JavaMovementMath choice = velocity.firstInLineage(JavaMovementMath.class);
        boolean toggle = choice != null && choice.vanilla != player.trigHandler.isVanillaMath();
        if (toggle) player.trigHandler.toggleShitMath();
        try {
            return operation.get();
        } finally {
            if (toggle) player.trigHandler.toggleShitMath();
        }
    }
}
