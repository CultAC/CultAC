package ac.cult.blocksim.engine;

import ac.cult.blocksim.data.Box;

/** The block box calls this entity geometry with size=1 and bottom center=(.5,0,.5). */
public final class ShulkerGeometry {
    private ShulkerGeometry() { }
    public static Box progressBounds(float size, Direction direction, float progress, Vec3 position) {
        return progressDeltaBounds(size, direction, -1.0F, progress, position);
    }
    private static Box progressDeltaBounds(float size, Direction direction, float from, float to, Vec3 position) {
        double maximum = Math.max(from, to), minimum = Math.min(from, to);
        double[] low = {-size * 0.5, 0.0, -size * 0.5}, high = {size * 0.5, size, size * 0.5};
        int[] steps = {direction.x(), direction.y(), direction.z()};
        double[] offsets = {position.x(), position.y(), position.z()};
        for (int axis = 0; axis < 3; axis++) {
            double extend = steps[axis] * maximum * size, contract = -steps[axis] * (1.0 + minimum) * size;
            if (extend < 0) low[axis] += extend; else if (extend > 0) high[axis] += extend;
            if (contract < 0) low[axis] -= contract; else if (contract > 0) high[axis] -= contract;
            low[axis] += offsets[axis]; high[axis] += offsets[axis];
        }
        return new Box(low[0], low[1], low[2], high[0], high[1], high[2]);
    }
}
