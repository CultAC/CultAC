package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.prediction.api.BedrockActorOrderRange;

/**
 * How the order the client meets a move's solid actors in decides the move, axis by axis (bit per axis: X 1, Y 2,
 * Z 4). Along a crossed axis an actor's lower bound lies above another's upper bound, so the actor met last decides
 * it. A crossed axis is exact when the first phase crosses it with one actor reaching across from each side: every
 * order then ends at one of its two ends, and the actors bounding it from below were met last in the collected order
 * when {@code belowWon} holds. Along every crossed axis the move may end anywhere between the ends of the two orders
 * that meet the actors bounding it from one side last: {@code min*} and {@code max*} are those ends less the move's
 * own, summed over the phases that cross the axis.
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
        float maxZ) {
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
                first.maxZ + later.maxZ);
    }

    /** This crossing as one of a later phase, which decides no axis exactly. */
    BedrockActorOrderCrossing later() {
        return exactAxes == 0
                ? this
                : new BedrockActorOrderCrossing(crossedAxes, 0, 0, minX, minY, minZ, maxX, maxY, maxZ);
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
                (axes & 4) == 0 ? 0.0D : maxZ);
    }
}
