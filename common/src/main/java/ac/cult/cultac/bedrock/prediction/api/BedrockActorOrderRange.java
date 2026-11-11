package ac.cult.cultac.bedrock.prediction.api;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;

/**
 * Where, axis by axis, a move may end other than at its own end because of the order the client meets its solid actors
 * in: from {@code min} to {@code max} added to the end. An axis outside the range has both zero. Only an axis that more
 * than one actor reaches across from one side, a third axis, or one a later phase crosses has a range; the move's
 * velocity and collision are those of its own end, which every order shares along a collided axis.
 */
public record BedrockActorOrderRange(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    /** The end in range closest to {@code claimed}, both as moves from the same start. */
    public Vec3d closest(Vec3d end, Vec3d claimed) {
        return new Vec3d(
                clamp(claimed.x(), end.x() + minX, end.x() + maxX),
                clamp(claimed.y(), end.y() + minY, end.y() + maxY),
                clamp(claimed.z(), end.z() + minZ, end.z() + maxZ));
    }

    /** The width along each axis, as {@code x:0.059} for the axes with one. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        append(text, 'x', maxX - minX);
        append(text, 'y', maxY - minY);
        append(text, 'z', maxZ - minZ);
        return text.toString();
    }

    private static void append(StringBuilder text, char axis, double width) {
        if (width <= 0.0D) return;
        if (!text.isEmpty()) text.append(',');
        text.append(axis).append(':').append(String.format(java.util.Locale.ROOT, "%.3f", width));
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
