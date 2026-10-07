package ac.cult.blocksim.engine;

import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Fluid height and current over an adapter's compensated block/face queries. */
public final class FluidQueries {
    private static final java.util.List<Direction> HORIZONTAL = java.util.List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    // Preserve the model's existing process-wide FlowingFluid shape-cache lifetime.
    // Publish immutable snapshots; prediction threads never mutate a shared map.
    private static final AtomicReference<Map<SimFluidState, VoxelShape>> SHAPES = new AtomicReference<>(Map.of());
    public interface View {
        SimFluidState fluidAt(BlockPos pos);
        boolean blocksFlow(BlockPos pos);
        boolean isIce(BlockPos pos);
        boolean isFaceSturdy(BlockPos pos, Direction face);
    }

    private FluidQueries() { }

    /** Pinned 26.3 FlowingFluid.getShape keeps the height from the first lookup of each fluid state. */
    public static VoxelShape shape(SimFluidState state, Supplier<SimFluidState> above) {
        if (state.isEmpty()) return Shapes.empty();
        for (;;) {
            var snapshot = SHAPES.get();
            var shape = snapshot.get(state);
            if (shape != null) return shape;
            shape = Shapes.create(0, 0, 0, 1, state.height(above.get()), 1);
            var updated = new HashMap<>(snapshot);
            updated.put(state, shape);
            if (SHAPES.compareAndSet(snapshot, Map.copyOf(updated))) return shape;
        }
    }

    /** Pinned 26.3 FlowingFluid.getFlow: preserve float differences and double accumulation. */
    public static Vec3 flow(View world, BlockPos origin, SimFluidState source) {
        if (source.isEmpty()) return new Vec3(0, 0, 0);
        double x = 0, z = 0;
        for (Direction face : HORIZONTAL) {
            var adjacent = origin.relative(face);
            var other = world.fluidAt(adjacent);
            if (!affects(source, other)) continue;
            float slope = 0, height = other.ownHeight();
            if (height > 0) {
                slope = source.ownHeight() - height;
            } else if (height == 0 && !world.blocksFlow(adjacent)) {
                var below = world.fluidAt(adjacent.relative(Direction.DOWN));
                if (affects(source, below) && below.ownHeight() > 0) {
                    slope = source.ownHeight() - (below.ownHeight() - 0.8888889F);
                }
            }
            if (slope != 0) {
                x += face.x() * slope;
                z += face.z() * slope;
            }
        }
        var result = new Vec3(x, 0, z);
        if (source.falling()) {
            for (Direction face : HORIZONTAL) {
                var adjacent = origin.relative(face);
                if (solidFace(world, source, adjacent, face)
                    || solidFace(world, source, adjacent.relative(Direction.UP), face)) {
                    result = normalize(result).addScaled(new Vec3(0, -6, 0), 1);
                    break;
                }
            }
        }
        return normalize(result);
    }

    private static boolean affects(SimFluidState source, SimFluidState other) {
        return other.isEmpty() || source.isSame(other);
    }

    private static boolean solidFace(View world, SimFluidState source, BlockPos pos, Direction face) {
        if (source.isSame(world.fluidAt(pos))) return false;
        return face == Direction.UP || !world.isIce(pos) && world.isFaceSturdy(pos, face);
    }

    private static Vec3 normalize(Vec3 vector) {
        double length = Math.sqrt(vector.lengthSquared());
        return length < 1.0E-5F ? new Vec3(0, 0, 0)
            : new Vec3(vector.x() / length, vector.y() / length, vector.z() / length);
    }
}
