package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.utils.math.Vec3;

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

        return offset(start, 0, yDiff, 0, "Vertical");
    }

    public static PredVector handleCircular(PredVector start, Vec3 end, double radius) {
        // Move the start by circle amount towards the end vector, but only in the xz plane
        // Bit-identical to Vec3#subtract, length, normalize and scale, including normalize's 1.0E-5F cutoff
        double dx = end.x - start.x;
        double dz = end.z - start.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance == 0) return start;
        double scale = Math.min(radius, distance);
        if (distance < 1.0E-5F) return offset(start, 0.0D * scale, 0.0D * scale, 0.0D * scale, "circular");
        return offset(start, dx / distance * scale, 0.0D * scale, dz / distance * scale, "circular");
    }

    private static PredVector offset(PredVector start, double x, double y, double z, String reason) {
        return start.withXYZOrSelf(start.x + x, start.y + y, start.z + z, reason);
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
