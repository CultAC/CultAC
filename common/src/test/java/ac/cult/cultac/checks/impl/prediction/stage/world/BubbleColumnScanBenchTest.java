package ac.cult.cultac.checks.impl.prediction.stage.world;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.BubbleColumnData;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.Collisions;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Thread CPU and allocation of one bubble column scan, with no column in reach and inside a column: the scan with a
 * visited set, four counters and a capturing visitor per call versus the current one, interleaved per round. Runs only
 * with {@code CULT_SMALL_BENCH} set.
 */
class BubbleColumnScanBenchTest {
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final long ROUND_NANOS = 500_000_000L;

    private final SimpleCollisionBox fromScratch = new SimpleCollisionBox();
    private final SimpleCollisionBox toScratch = new SimpleCollisionBox();
    private final SimpleCollisionBox sweptScratch = new SimpleCollisionBox();

    @Test
    void scanCost() throws Exception {
        Assumptions.assumeTrue(System.getenv("CULT_SMALL_BENCH") != null);
        int repetitions = Integer.parseInt(System.getenv().getOrDefault("CULT_SMALL_BENCH_REPS", "9"));
        Class<?> fixtureType = Class.forName("ac.cult.cultac.network.JavaTickFixture");
        Constructor<?> constructor = fixtureType.getDeclaredConstructor();
        constructor.setAccessible(true);
        try (AutoCloseable fixture = (AutoCloseable) constructor.newInstance()) {
            var playerField = fixtureType.getDeclaredField("player");
            playerField.setAccessible(true);
            CultPlayer player = (CultPlayer) playerField.get(fixture);
            int up = BlockProps.DRAG.with(BlockIds.BUBBLE_COLUMN.defaultState(), false);
            for (int y = 60; y <= 70; y++) player.compensatedWorld.updateBlock(new BlockPos(20, y, 20), up);
            player.compensatedWorld.updateBlock(new BlockPos(20, 71, 20), BlockIds.WATER.defaultState());
            WorldStageBuilder builder = new WorldStageBuilder();
            Object[][] cases = {
                {"miss", new Vec3(0.3, 64.0, 0.3), new Vec3(0.05, -0.0784, 0.04)},
                {"hit", new Vec3(20.5, 64.0, 20.5), new Vec3(0.02, 0.1, 0.01)},
            };
            for (Object[] c : cases) {
                Vec3 from = (Vec3) c[1], delta = (Vec3) c[2];
                SimulationContext context = mock(SimulationContext.class, withSettings().stubOnly());
                when(context.getStart()).thenReturn(from);
                when(context.getTarget()).thenReturn(delta);
                when(context.getMaxWidth()).thenReturn(0.6F);
                when(context.getMaxHeight()).thenReturn(1.8F);
                Vec3 to = from.add(delta);
                double[][] cpu = new double[2][repetitions];
                double[][] bytes = new double[2][repetitions];
                long sink = 0;
                for (int r = -4; r < repetitions; r++) {
                    for (int path = 0; path < 2; path++) {
                        long[] round = round(builder, player, to, context, path == 1);
                        sink += round[2];
                        if (r >= 0) {
                            cpu[path][r] = round[0];
                            bytes[path][r] = round[1];
                        }
                    }
                }
                if (sink == 0 && c[0].equals("hit")) throw new AssertionError();
                System.out.println(line("bubble " + c[0] + " base cpu ns", cpu[0], 1000));
                System.out.println(line("bubble " + c[0] + " new  cpu ns", cpu[1], 1000));
                System.out.println(line("bubble " + c[0] + " base bytes", bytes[0], 1000));
                System.out.println(line("bubble " + c[0] + " new  bytes", bytes[1], 1000));
            }
        }
    }

    private long[] round(WorldStageBuilder builder, CultPlayer player, Vec3 to, SimulationContext context, boolean next) {
        long calls = 0, sink = 0;
        long thread = Thread.currentThread().threadId();
        long wallStart = System.nanoTime();
        long start = THREADS.getCurrentThreadCpuTime();
        long allocStart = THREADS.getThreadAllocatedBytes(thread);
        while (System.nanoTime() - wallStart < ROUND_NANOS) {
            for (int i = 0; i < 10_000; i++) {
                BubbleColumnData data = next ? builder.computeBubbleColumns(player, to, context) : base(player, to, context);
                if (data.hasBubbleColumn()) sink++;
            }
            calls += 10_000;
        }
        long cpu = THREADS.getCurrentThreadCpuTime() - start;
        long alloc = THREADS.getThreadAllocatedBytes(thread) - allocStart;
        return new long[] {cpu * 1000 / calls, alloc * 1000 / calls, sink};
    }

    private BubbleColumnData base(CultPlayer player, Vec3 to, SimulationContext context) {
        Vec3 from = context.getStart();
        int[] pushUpwards = new int[1];
        int[] pushDownwards = new int[1];
        int[] airUpwards = new int[1];
        int[] airDownwards = new int[1];
        Set<Long> visited = new HashSet<>();
        double mx = to.x - from.x, my = to.y - from.y, mz = to.z - from.z;
        if (mx * mx + my * my + mz * mz > 0.0) {
            double sx = from.x, sy = from.y, sz = from.z;
            Vec3 target = context.getTarget();
            List<Direction.Axis> order = Math.abs(target.x) < Math.abs(target.z)
                    ? List.of(Direction.Axis.Y, Direction.Axis.Z, Direction.Axis.X)
                    : List.of(Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z);
            for (Direction.Axis axis : order) {
                double move = axis == Direction.Axis.X ? mx : axis == Direction.Axis.Y ? my : mz;
                if (move == 0.0) continue;
                double nx = axis == Direction.Axis.X ? sx + move : sx;
                double ny = axis == Direction.Axis.Y ? sy + move : sy;
                double nz = axis == Direction.Axis.Z ? sz + move : sz;
                accumulate(player, context, sx, sy, sz, nx, ny, nz, visited, pushUpwards, pushDownwards, airUpwards, airDownwards);
                sx = nx;
                sy = ny;
                sz = nz;
            }
        } else {
            accumulate(player, context, from.x, from.y, from.z, to.x, to.y, to.z, visited, pushUpwards, pushDownwards, airUpwards, airDownwards);
        }
        return new BubbleColumnData(airUpwards[0], pushUpwards[0], airDownwards[0], pushDownwards[0]);
    }

    private void accumulate(
            CultPlayer player,
            SimulationContext context,
            double fx,
            double fy,
            double fz,
            double tx,
            double ty,
            double tz,
            Set<Long> visited,
            int[] pushUpwards,
            int[] pushDownwards,
            int[] airUpwards,
            int[] airDownwards) {
        SimpleCollisionBox fromBox = WorldStageBuilder.bubbleColumnBoxAt(fromScratch, player, context, fx, fy, fz);
        SimpleCollisionBox toBox = WorldStageBuilder.bubbleColumnBoxAt(toScratch, player, context, tx, ty, tz);
        SimpleCollisionBox swept = sweptScratch.set(fromBox).union(toBox);
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        boolean longMove = dx * dx + dy * dy + dz * dz > 0.9999900000002526D * 0.9999900000002526D;
        Collisions.hasState(player, swept, state -> BlockIds.is(state, BlockIds.BUBBLE_COLUMN), (state, pos) -> {
            if (!BlockIds.is(state, BlockIds.BUBBLE_COLUMN)) return false;
            boolean precise = longMove || intersects(toBox, 1.0E-5F, pos);
            boolean touches = precise
                    || Collisions.sweptFullBlockEntityInside(fromBox, toBox, pos.getX(), pos.getY(), pos.getZ());
            if (!touches || !precise || !visited.add(pos.asLong())) return false;
            int above = player.compensatedWorld.getBlockStateIdAt(pos.getX(), pos.getY() + 1, pos.getZ());
            boolean down = BlockProps.DRAG.has(state) && BlockProps.DRAG.booleanValue(state);
            boolean air = player.compensatedWorld.geometry().collision(above, pos).isEmpty()
                    && ClientFluidQueries.modelFluid(above).isEmpty();
            if (down) {
                if (air) airDownwards[0]++;
                else pushDownwards[0]++;
            } else {
                if (air) airUpwards[0]++;
                else pushUpwards[0]++;
            }
            return false;
        });
    }

    private static boolean intersects(SimpleCollisionBox box, double inset, BlockPos pos) {
        return box.minX + inset < pos.getX() + 1.0D
                && box.maxX - inset > pos.getX()
                && box.minY + inset < pos.getY() + 1.0D
                && box.maxY - inset > pos.getY()
                && box.minZ + inset < pos.getZ() + 1.0D
                && box.maxZ - inset > pos.getZ();
    }

    private static String line(String name, double[] values, double scale) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return String.format(
                Locale.ROOT,
                "%-28s median %9.2f  min %9.2f  max %9.2f  (%d reps)",
                name,
                sorted[sorted.length / 2] / scale,
                sorted[0] / scale,
                sorted[sorted.length - 1] / scale,
                sorted.length);
    }
}
