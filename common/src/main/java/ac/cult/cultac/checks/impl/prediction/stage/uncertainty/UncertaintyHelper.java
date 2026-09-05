package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import net.minecraft.world.phys.Vec3;

public class UncertaintyHelper {

    public static PredVector handleDownwards(PredVector start, Vec3 end, double offset) {
        if (end.y < start.y) {
            return handleVertical(start, end, offset);
        }
        return start;
    }

    public static PredVector handleVertical(PredVector start, Vec3 end, double radius) {
        double yDiff = end.y - start.y;
        if (Math.abs(yDiff) > radius) {
            yDiff = radius * Math.signum(yDiff);
        }

        return start.add(0, yDiff, 0, "Vertical");
    }

    public static PredVector handleCircular(PredVector start, Vec3 end, double radius) {
        // Move the start by circle amount towards the end vector, but only in the xz plane
        Vec3 diff = new Vec3(end.x, 0, end.z).subtract(new Vec3(start.x, 0, start.z));
        double distance = diff.length();
        if (distance == 0) return start;
        return start.add(diff.normalize().scale(Math.min(radius, distance)), "circular");
    }

    public static PredVector handleSpherical(PredVector start, Vec3 end, double radius) {
        // Move the start by circle amount towards the end vector
        double yDiff = end.y - start.y;
        if (Math.abs(yDiff) > radius) {
            yDiff = radius * Math.signum(yDiff);
            return start.add(0, yDiff, 0, "spherical");
        } else {
            // 3d distance = sqrt(x^2 + y^2 + z^2)
            // radius = sqrt(x^2 + y^2 + yDiff^2)
            // radius^2 = x^2 + y^2 + yDiff^2
            radius = radius * radius;
            radius -= yDiff * yDiff;
            // Enough Y distance to reach the target
            return handleCircular(start, end, Math.sqrt(radius)).withY(end.y, "spherical");
        }
    }
}
