package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.List;

/** Opens the auto-jump look-ahead scans to tests in other packages. */
public final class AutoJumpGeometryAccess {
    private AutoJumpGeometryAccess() {}

    /** A test of one collision box's Y bounds. */
    public interface Heights {
        boolean test(double minY, double maxY);
    }

    public static List<SimpleCollisionBox> blockCollisions(CultPlayer player, SimpleCollisionBox query) {
        return JavaAutoJumpGeometry.blockCollisions(player, query);
    }

    public static boolean anyBlockCollision(CultPlayer player, SimpleCollisionBox query, Heights heights) {
        return JavaAutoJumpGeometry.anyBlockCollision(player, query, heights::test);
    }
}
