package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.utils.math.Vec3;

public class UncertaintyHelper {

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

    /**
     * The horizontal input allowance: moves the start up to {@code radius} towards the end at any distance, as
     * Entity#getInputVector normalizes the key impulse and never the scaled move.
     */
    public static PredVector handleCircularInput(PredVector start, Vec3 end, double radius) {
        double dx = end.x - start.x;
        double dz = end.z - start.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance == 0) return start;
        double scale = Math.min(radius, distance);
        return offset(start, dx / distance * scale, 0.0D, dz / distance * scale, "circular");
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

    /** {@link #handleVertical} on the position in {@code xyz} towards an end at {@code endY}, with the same arithmetic. */
    static void verticalInto(double[] xyz, double endY, double radius) {
        double yDiff = endY - xyz[1];
        if (Math.abs(yDiff) > radius) {
            yDiff = radius * Math.signum(yDiff);
        }

        offsetInto(xyz, 0, yDiff, 0);
    }

    /** {@link #handleCircular} on the position in {@code xyz} towards an end at {@code endX}, {@code endZ}. */
    static void circularInto(double[] xyz, double endX, double endZ, double radius) {
        double dx = endX - xyz[0];
        double dz = endZ - xyz[2];
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance == 0) return;
        double scale = Math.min(radius, distance);
        if (distance < 1.0E-5F) {
            offsetInto(xyz, 0.0D * scale, 0.0D * scale, 0.0D * scale);
            return;
        }
        offsetInto(xyz, dx / distance * scale, 0.0D * scale, dz / distance * scale);
    }

    private static void offsetInto(double[] xyz, double x, double y, double z) {
        xyz[0] = xyz[0] + x;
        xyz[1] = xyz[1] + y;
        xyz[2] = xyz[2] + z;
    }

    /** Coordinate {@code axis} (0 x, 1 y, 2 z) of {@link #handleSpherical}'s result, with the same arithmetic. */
    public static double sphericalCoordinate(PredVector start, Vec3 end, double radius, int axis) {
        return sphericalCoordinate(start.x, start.y, start.z, end.x, end.y, end.z, radius, axis);
    }

    /** {@link #sphericalCoordinate} for a start at {@code x}, {@code y}, {@code z} and an end at the end coordinates. */
    static double sphericalCoordinate(
            double x, double y, double z, double endX, double endY, double endZ, double radius, int axis) {
        double yDiff = endY - y;
        if (Math.abs(yDiff) > radius) {
            yDiff = radius * Math.signum(yDiff);
            return axis == 0 ? x + 0 : axis == 1 ? y + yDiff : z + 0;
        }
        radius = radius * radius;
        radius -= yDiff * yDiff;
        if (axis == 1) return endY;
        return circularCoordinate(x, z, endX, endZ, Math.sqrt(radius), axis);
    }

    private static double circularCoordinate(double x, double z, double endX, double endZ, double radius, int axis) {
        double dx = endX - x;
        double dz = endZ - z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance == 0) return axis == 0 ? x : z;
        double scale = Math.min(radius, distance);
        if (distance < 1.0E-5F) return axis == 0 ? x + 0.0D * scale : z + 0.0D * scale;
        return axis == 0 ? x + dx / distance * scale : z + dz / distance * scale;
    }
}
