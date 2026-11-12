package ac.cult.cultac.bedrock.prediction.api;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;

/**
 * Where, axis by axis, a move may end other than at its own end because of its solid actors: from {@code min} to
 * {@code max} added to the end. An axis outside the range has both zero. An axis that more than one actor reaches
 * across from one side, a third axis, or one a later phase crosses has a range from the order the client meets them
 * in; an axis a moving boat or happy ghast ended has one from that actor's buffer ({@code bufferedAxes}, bit per
 * axis: X 1, Y 2, Z 4). Those axes share {@code bufferDistance}; {@code unbuffered} retains the ends actor ordering
 * permits without borrowing. The move's velocity and collision are those of its own end, which every end shares along a collided axis.
 */
public record BedrockActorOrderRange(
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        int bufferedAxes,
        double bufferDistance,
        BedrockActorOrderRange unbuffered) {
    public BedrockActorOrderRange(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this(minX, minY, minZ, maxX, maxY, maxZ, 0, 0.0D, null);
    }

    /** The closest end, subject to the one distance budget shared by all buffered axes. */
    public Vec3d closest(Vec3d end, Vec3d claimed) {
        Vec3d clipped = boxClosest(end, claimed);
        if (bufferedAxes == 0) return clipped;
        Vec3d base = baseClosest(end, claimed);
        Vec3d offset = bufferedOffset(clipped.subtract(base));
        if (offset.length() <= bufferDistance) return clipped;

        // Project onto the distance budget and the box together. A block/request bound may stop one axis before
        // the distance budget does; fix that axis at its bound, then share the remaining budget over the others.
        Vec3d requested = bufferedOffset(claimed.subtract(base));
        double[] delta = {requested.x(), requested.y(), requested.z()};
        double[] limit = {Math.abs(offset.x()), Math.abs(offset.y()), Math.abs(offset.z())};
        double[] selected = new double[3];
        boolean[] fixed = new boolean[3];
        double remaining = bufferDistance * bufferDistance;
        for (int pass = 0; pass < 3; pass++) {
            double squared = 0.0D;
            for (int axis = 0; axis < 3; axis++) if (!fixed[axis]) squared += delta[axis] * delta[axis];
            if (squared == 0.0D) break;
            double scale = Math.sqrt(Math.max(0.0D, remaining) / squared);
            boolean bounded = false;
            for (int axis = 0; axis < 3; axis++) {
                if (fixed[axis] || Math.abs(delta[axis]) * scale <= limit[axis]) continue;
                selected[axis] = Math.copySign(limit[axis], delta[axis]);
                remaining -= limit[axis] * limit[axis];
                fixed[axis] = true;
                bounded = true;
            }
            if (bounded) continue;
            for (int axis = 0; axis < 3; axis++) if (!fixed[axis]) selected[axis] = delta[axis] * scale;
            break;
        }
        return new Vec3d(
                (bufferedAxes & 1) == 0 ? clipped.x() : base.x() + selected[0],
                (bufferedAxes & 2) == 0 ? clipped.y() : base.y() + selected[1],
                (bufferedAxes & 4) == 0 ? clipped.z() : base.z() + selected[2]);
    }

    /** The distance the selected end borrows beyond the ends the actor order itself allows. */
    public double buffered(Vec3d end, Vec3d claimed) {
        if (bufferedAxes == 0) return 0.0D;
        Vec3d selected = closest(end, claimed);
        return bufferedOffset(selected.subtract(baseClosest(end, selected))).length();
    }

    private Vec3d bufferedOffset(Vec3d offset) {
        return new Vec3d(
                (bufferedAxes & 1) == 0 ? 0.0D : offset.x(),
                (bufferedAxes & 2) == 0 ? 0.0D : offset.y(),
                (bufferedAxes & 4) == 0 ? 0.0D : offset.z());
    }

    private Vec3d baseClosest(Vec3d end, Vec3d claimed) {
        return unbuffered == null ? end : unbuffered.boxClosest(end, claimed);
    }

    private Vec3d boxClosest(Vec3d end, Vec3d claimed) {
        return new Vec3d(
                clamp(claimed.x(), end.x() + minX, end.x() + maxX),
                clamp(claimed.y(), end.y() + minY, end.y() + maxY),
                clamp(claimed.z(), end.z() + minZ, end.z() + maxZ));
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
        text.append(axis).append(buffered ? '~' : ':').append(String.format(java.util.Locale.ROOT, "%.3f", width));
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
