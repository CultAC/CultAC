package ac.cult.blocksim.engine;

import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Fluid height and current over an adapter's compensated block/face queries. */
public final class FluidQueries {
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    // Preserve the model's existing process-wide FlowingFluid shape-cache lifetime.
    // Publish immutable snapshots; prediction threads never mutate a shared map.
    private static final AtomicReference<Map<SimFluidState, VoxelShape>> SHAPES = new AtomicReference<>(Map.of());
    public interface View {
        SimFluidState fluidAt(int x, int y, int z);
        boolean blocksFlow(int x, int y, int z);
        boolean isIce(int x, int y, int z);
        boolean isFaceSturdy(int x, int y, int z, Direction face);
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
        double[] out = new double[3];
        flow(world, origin.x(), origin.y(), origin.z(), source, out);
        return new Vec3(out[0], out[1], out[2]);
    }

    /** {@link #flow(View, BlockPos, SimFluidState)} at {@code x, y, z} into {@code out} as x, y, z, allocating nothing. */
    public static void flow(View world, int x, int y, int z, SimFluidState source, double[] out) {
        out[0] = 0;
        out[1] = 0;
        out[2] = 0;
        if (source.isEmpty()) return;
        double flowX = 0, flowY = 0, flowZ = 0;
        for (Direction face : HORIZONTAL) {
            int adjacentX = x + face.x(), adjacentZ = z + face.z();
            var other = world.fluidAt(adjacentX, y, adjacentZ);
            if (!affects(source, other)) continue;
            float slope = 0, height = other.ownHeight();
            if (height > 0) {
                slope = source.ownHeight() - height;
            } else if (height == 0 && !world.blocksFlow(adjacentX, y, adjacentZ)) {
                var below = world.fluidAt(adjacentX, y - 1, adjacentZ);
                if (affects(source, below) && below.ownHeight() > 0) {
                    slope = source.ownHeight() - (below.ownHeight() - 0.8888889F);
                }
            }
            if (slope != 0) {
                flowX += face.x() * slope;
                flowZ += face.z() * slope;
            }
        }
        if (source.falling()) {
            for (Direction face : HORIZONTAL) {
                int adjacentX = x + face.x(), adjacentZ = z + face.z();
                if (solidFace(world, source, adjacentX, y, adjacentZ, face)
                    || solidFace(world, source, adjacentX, y + 1, adjacentZ, face)) {
                    double length = Math.sqrt(flowX * flowX + flowY * flowY + flowZ * flowZ);
                    if (length < 1.0E-5F) {
                        flowX = 0;
                        flowY = 0;
                        flowZ = 0;
                    } else {
                        flowX /= length;
                        flowY /= length;
                        flowZ /= length;
                    }
                    flowY += -6;
                    break;
                }
            }
        }
        double length = Math.sqrt(flowX * flowX + flowY * flowY + flowZ * flowZ);
        if (length < 1.0E-5F) return;
        out[0] = flowX / length;
        out[1] = flowY / length;
        out[2] = flowZ / length;
    }

    private static boolean affects(SimFluidState source, SimFluidState other) {
        return other.isEmpty() || source.isSame(other);
    }

    private static boolean solidFace(View world, SimFluidState source, int x, int y, int z, Direction face) {
        if (source.isSame(world.fluidAt(x, y, z))) return false;
        return face == Direction.UP || !world.isIce(x, y, z) && world.isFaceSturdy(x, y, z, face);
    }
}
