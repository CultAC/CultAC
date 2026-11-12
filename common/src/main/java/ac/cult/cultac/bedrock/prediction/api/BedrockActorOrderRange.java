package ac.cult.cultac.bedrock.prediction.api;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;

/**
 * Where, axis by axis, a move may end other than at its own end because of its solid actors: from {@code min} to
 * {@code max} added to the end. An axis outside the range has both zero. An axis that more than one actor reaches
 * across from one side, a third axis, or one a later phase crosses has a range from the order the client meets them
 * in; an axis a moving boat or happy ghast ended has one from that actor's buffer ({@code bufferedAxes}, bit per
 * axis: X 1, Y 2, Z 4). The move's velocity and collision are those of its own end, which every end shares along a collided axis.
 */
public record BedrockActorOrderRange(
        double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int bufferedAxes) {
    public BedrockActorOrderRange(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this(minX, minY, minZ, maxX, maxY, maxZ, 0);
    }

    /** The end in range closest to {@code claimed}, both as moves from the same start. */
    public Vec3d closest(Vec3d end, Vec3d claimed) {
        return new Vec3d(
                clamp(claimed.x(), end.x() + minX, end.x() + maxX),
                clamp(claimed.y(), end.y() + minY, end.y() + maxY),
                clamp(claimed.z(), end.z() + minZ, end.z() + maxZ));
    }

    /** How far along the buffered axes the end in range closest to {@code claimed} lies from {@code end}. */
    public double buffered(Vec3d end, Vec3d claimed) {
        if (bufferedAxes == 0) return 0.0D;
        Vec3d off = closest(end, claimed).subtract(end);
        double x = (bufferedAxes & 1) == 0 ? 0.0D : off.x();
        double y = (bufferedAxes & 2) == 0 ? 0.0D : off.y();
        double z = (bufferedAxes & 4) == 0 ? 0.0D : off.z();
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** The width along each axis, as {@code x:0.059} for the axes with one, {@code z~0.006} for a buffered one. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        append(text, 'x', maxX - minX, (bufferedAxes & 1) != 0);
        append(text, 'y', maxY - minY, (bufferedAxes & 2) != 0);
        append(text, 'z', maxZ - minZ, (bufferedAxes & 4) != 0);
        return text.toString();
    }

    private static void append(StringBuilder text, char axis, double width, boolean buffered) {
        if (width <= 0.0D) return;
        if (!text.isEmpty()) text.append(',');
        text.append(axis)
                .append(buffered ? '~' : ':')
                .append(String.format(java.util.Locale.ROOT, "%.3f", width));
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
