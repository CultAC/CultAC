package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.prediction.api.BedrockActorOrderRange;

/**
 * How the order the client meets a move's solid actors in decides the move, axis by axis (bit per axis: X 1, Y 2,
 * Z 4). Along a crossed axis an actor's lower bound lies above another's upper bound, so the actor met last decides
 * it. A crossed axis is exact when the first phase crosses it with one actor reaching across from each side: every
 * order then ends at one of its two ends, and the actors bounding it from below were met last in the collected order
 * when {@code belowWon} holds. Along every crossed axis the move may end anywhere between the ends of the two orders
 * that meet the actors bounding it from one side last: {@code min*} and {@code max*} are those ends less the move's
 * own, summed over the phases that cross the axis. An axis in {@code bufferedAxes} is one a moving actor ended,
 * whose range also holds the ends that actor's buffer allows.
 */
public record BedrockActorOrderCrossing(
        int crossedAxes,
        int exactAxes,
        int belowWon,
        float minX,
        float minY,
        float minZ,
        float maxX,
        float maxY,
        float maxZ,
        int bufferedAxes,
        float bufferDistance,
        BedrockActorOrderCrossing unbuffered) {
    public BedrockActorOrderCrossing(
            int crossedAxes,
            int exactAxes,
            int belowWon,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            int bufferedAxes) {
        this(crossedAxes, exactAxes, belowWon, minX, minY, minZ, maxX, maxY, maxZ, bufferedAxes, 0.0F, null);
    }

    private BedrockActorOrderCrossing withoutBuffer() {
        return bufferedAxes == 0 ? this : unbuffered;
    }

    /** {@code first} followed by {@code later}'s phases; only the move's first phase crosses an axis exactly. */
    static BedrockActorOrderCrossing then(BedrockActorOrderCrossing first, BedrockActorOrderCrossing later) {
        if (later == null) return first;
        if (first == null) return later.later();
        return new BedrockActorOrderCrossing(
                first.crossedAxes | later.crossedAxes,
                first.exactAxes,
                first.belowWon,
                first.minX + later.minX,
                first.minY + later.minY,
                first.minZ + later.minZ,
                first.maxX + later.maxX,
                first.maxY + later.maxY,
                first.maxZ + later.maxZ,
                first.bufferedAxes | later.bufferedAxes,
                Math.max(first.bufferDistance, later.bufferDistance),
                first.bufferedAxes == 0 && later.bufferedAxes == 0
                        ? null
                        : then(first.withoutBuffer(), later.withoutBuffer()));
    }

    /** This crossing as one of a later phase, which decides no axis exactly. */
    BedrockActorOrderCrossing later() {
        return exactAxes == 0
                ? this
                : new BedrockActorOrderCrossing(
                        crossedAxes,
                        0,
                        0,
                        minX,
                        minY,
                        minZ,
                        maxX,
                        maxY,
                        maxZ,
                        bufferedAxes,
                        bufferDistance,
                        unbuffered == null ? null : unbuffered.later());
    }

    /**
     * {@code crossing}, or no crossing, with the ends {@code x}, {@code y} and {@code z} give along {@code axes}
     * added: the ends a moving actor's buffer allows along the axes it ended.
     */
    static BedrockActorOrderCrossing buffered(
            BedrockActorOrderCrossing crossing,
            int axes,
            BedrockCollisionClipper.BufferedEnds x,
            BedrockCollisionClipper.BufferedEnds y,
            BedrockCollisionClipper.BufferedEnds z,
            float bufferDistance) {
        if (axes == 0) return crossing;
        if (crossing == null) {
            return new BedrockActorOrderCrossing(
                    axes, 0, 0, x.min(), y.min(), z.min(), x.max(), y.max(), z.max(), axes, bufferDistance, null);
        }
        return new BedrockActorOrderCrossing(
                crossing.crossedAxes | axes,
                crossing.exactAxes,
                crossing.belowWon,
                Math.min(crossing.minX, x.min()),
                Math.min(crossing.minY, y.min()),
                Math.min(crossing.minZ, z.min()),
                Math.max(crossing.maxX, x.max()),
                Math.max(crossing.maxY, y.max()),
                Math.max(crossing.maxZ, z.max()),
                crossing.bufferedAxes | axes,
                Math.max(crossing.bufferDistance, bufferDistance),
                crossing.withoutBuffer());
    }

    /** The range of the move's end along the crossed axes outside {@code ordered}, or null when there are none. */
    BedrockActorOrderRange range(int ordered) {
        int axes = crossedAxes & ~ordered;
        if (axes == 0) return null;
        return new BedrockActorOrderRange(
                (axes & 1) == 0 ? 0.0D : minX,
                (axes & 2) == 0 ? 0.0D : minY,
                (axes & 4) == 0 ? 0.0D : minZ,
                (axes & 1) == 0 ? 0.0D : maxX,
                (axes & 2) == 0 ? 0.0D : maxY,
                (axes & 4) == 0 ? 0.0D : maxZ,
                bufferedAxes & axes,
                bufferDistance,
                unbuffered == null ? null : unbuffered.range(ordered));
    }
}
