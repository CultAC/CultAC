package ac.cult.cultac.checks;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;

public final class EyePositions {
    private EyePositions() {}

    public static SimpleCollisionBox box(List<Vec3> origins, List<Double> eyeHeights) {
        double minEyeHeight = Double.MAX_VALUE;
        double maxEyeHeight = Double.MIN_VALUE;
        for (double height : eyeHeights) {
            minEyeHeight = Math.min(minEyeHeight, height);
            maxEyeHeight = Math.max(maxEyeHeight, height);
        }

        SimpleCollisionBox box = null;
        for (Vec3 origin : origins) {
            SimpleCollisionBox eyes = new SimpleCollisionBox(
                    origin.x, origin.y + minEyeHeight, origin.z, origin.x, origin.y + maxEyeHeight, origin.z);
            box = box == null ? eyes : box.union(eyes);
        }
        return box;
    }
}
