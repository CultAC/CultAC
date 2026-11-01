package ac.cult.cultac.checks;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.List;

public final class EyePositions {
    private EyePositions() {}

    public static SimpleCollisionBox box(CultPlayer player, List<Double> eyeHeights) {
        double minEyeHeight = Double.MAX_VALUE;
        double maxEyeHeight = Double.MIN_VALUE;
        for (double height : eyeHeights) {
            minEyeHeight = Math.min(minEyeHeight, height);
            maxEyeHeight = Math.max(maxEyeHeight, height);
        }

        return new SimpleCollisionBox(
                player.x, player.y + minEyeHeight, player.z, player.x, player.y + maxEyeHeight, player.z);
    }
}
