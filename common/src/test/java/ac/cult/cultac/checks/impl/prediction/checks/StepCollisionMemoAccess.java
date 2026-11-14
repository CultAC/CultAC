package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;

/** The memo's package-private queries, for tests in other packages. */
public final class StepCollisionMemoAccess {
    private StepCollisionMemoAccess() {}

    public static boolean isEmpty(StepCollisionMemo memo, CultPlayer player, SimpleCollisionBox box) {
        return memo.isEmpty(player, box);
    }

    public static boolean collidesDirectlyBelow(StepCollisionMemo memo, CultPlayer player, SimpleCollisionBox box) {
        return memo.collidesDirectlyBelow(player, box);
    }
}
