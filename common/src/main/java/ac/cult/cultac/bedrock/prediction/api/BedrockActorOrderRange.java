package ac.cult.cultac.bedrock.prediction.api;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;

/**
 * Where, axis by axis, a move may end other than at its own end because of its solid actors: from {@code min} to
 * {@code max} added to the end. An axis outside the range has both zero. An axis that more than one actor reaches
 * across from one side, a third axis, or one a later phase crosses has a range from the order the client meets them
 * in; an axis a moving boat or happy ghast ended has one from that actor's buffer ({@code bufferedAxes}, bit per
 * axis: X 1, Y 2, Z 4). Those axes share {@code bufferDistance}; {@code unbuffered} retains the ends actor ordering
 * permits without borrowing. For a contact-position candidate it also records the untranslated actor's reference,
 * which can lie outside the requested move; reaching the contact then has a minimum charge. The move's velocity and collision are those of its own end, which every end shares along a collided axis.
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

        // Project onto the box and the shared sphere together. Contact onset may require a minimum
        // translation before an endpoint is reachable, so the reference can lie outside this box.
        Vec3d requested = bufferedOffset(claimed.subtract(base));
        double[] delta = {requested.x(), requested.y(), requested.z()};
        double[] low = {end.x() + minX - base.x(), end.y() + minY - base.y(), end.z() + minZ - base.z()};
        double[] high = {end.x() + maxX - base.x(), end.y() + maxY - base.y(), end.z() + maxZ - base.z()};
        double maximum = 0.0D;
        for (int axis = 0; axis < 3; axis++) {
            if ((bufferedAxes & 1 << axis) == 0) low[axis] = high[axis] = 0.0D;
            maximum = Math.max(maximum, Math.abs(delta[axis]));
        }
        if (maximum == 0.0D) throw new IllegalStateException("Actor contact has no feasible budget projection");
        double[] direction = {delta[0] / maximum, delta[1] / maximum, delta[2] / maximum};
        double squaredBudget = bufferDistance * bufferDistance;
        if (squared(0.0D, direction, low, high) > squaredBudget)
            throw new IllegalStateException("Actor contact needs more than its position budget");
        double lower = 0.0D;
        double upper = Math.min(maximum, Math.max(bufferDistance, Double.MIN_NORMAL));
        while (upper < maximum && squared(upper, direction, low, high) < squaredBudget)
            upper = Math.min(maximum, upper * 2.0D);
        for (int pass = 0; pass < 64; pass++) {
            double middle = lower + (upper - lower) * 0.5D;
            if (squared(middle, direction, low, high) <= squaredBudget) lower = middle;
            else upper = middle;
        }
        double[] selected = new double[3];
        for (int axis = 0; axis < 3; axis++) selected[axis] = clamp(direction[axis] * lower, low[axis], high[axis]);
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

    private static double squared(double scale, double[] direction, double[] low, double[] high) {
        double squared = 0.0D;
        for (int axis = 0; axis < 3; axis++) {
            double value = clamp(direction[axis] * scale, low[axis], high[axis]);
            squared += value * value;
        }
        return squared;
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
