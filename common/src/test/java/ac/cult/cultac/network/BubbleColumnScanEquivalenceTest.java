package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.BubbleColumnData;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The bubble column scan counts what a scan with a fresh visited set and fresh counters per call counts, on random
 * worlds of upward and downward columns under air, water and stone, and an empty scan is one shared value.
 */
class BubbleColumnScanEquivalenceTest {
    /** The scan as it stood with a visited set and four counters allocated per call. */
    private static BubbleColumnData reference(CultPlayer player, Vec3 to, SimulationContext context) {
        Vec3 from = context.getStart();
        int[] counts = new int[4];
        Set<Long> visited = new HashSet<>();
        double mx = to.x - from.x, my = to.y - from.y, mz = to.z - from.z;
        if (mx * mx + my * my + mz * mz > 0.0) {
            double sx = from.x, sy = from.y, sz = from.z;
            Vec3 target = context.getTarget();
            List<Direction.Axis> order = Math.abs(target.x) < Math.abs(target.z)
                    ? List.of(Direction.Axis.Y, Direction.Axis.Z, Direction.Axis.X)
                    : List.of(Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z);
            for (Direction.Axis axis : order) {
                double move = switch (axis) {
                    case X -> mx;
                    case Y -> my;
                    case Z -> mz;
                };
                if (move == 0.0) continue;
                Direction direction = switch (axis) {
                    case X -> Direction.EAST;
                    case Y -> Direction.UP;
                    case Z -> Direction.SOUTH;
                };
                double nx = sx + move * direction.getModX();
                double ny = sy + move * direction.getModY();
                double nz = sz + move * direction.getModZ();
                accumulate(player, context, sx, sy, sz, nx, ny, nz, visited, counts);
                sx = nx;
                sy = ny;
                sz = nz;
            }
        } else {
            accumulate(player, context, from.x, from.y, from.z, to.x, to.y, to.z, visited, counts);
        }
        return new BubbleColumnData(counts[0], counts[3], counts[1], counts[2]);
    }

    // counts: 0 air up, 1 air down, 2 push down, 3 push up
    private static void accumulate(
            CultPlayer player,
            SimulationContext context,
            double fx,
            double fy,
            double fz,
            double tx,
            double ty,
            double tz,
            Set<Long> visited,
            int[] counts) {
        SimpleCollisionBox fromBox = GetBoundingBox.getBoundingBoxFromPosAndSize(
                fx, fy, fz, context.getMaxWidth(), context.getMaxHeight());
        SimpleCollisionBox toBox = GetBoundingBox.getBoundingBoxFromPosAndSize(
                tx, ty, tz, context.getMaxWidth(), context.getMaxHeight());
        SimpleCollisionBox swept = fromBox.copy().union(toBox);
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
            if (down) counts[air ? 1 : 2]++;
            else counts[air ? 0 : 3]++;
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

    private static SimulationContext context(Vec3 from, Vec3 target) {
        SimulationContext context = mock(SimulationContext.class);
        when(context.getStart()).thenReturn(from);
        when(context.getTarget()).thenReturn(target);
        when(context.getMaxWidth()).thenReturn(0.6F);
        when(context.getMaxHeight()).thenReturn(1.8F);
        return context;
    }

    @Test
    void countsAsTheFreshScan() throws Exception {
        Method scan = WorldStageBuilder.class.getDeclaredMethod(
                "computeBubbleColumns", CultPlayer.class, Vec3.class, SimulationContext.class);
        scan.setAccessible(true);
        int up = BlockProps.DRAG.with(BlockIds.BUBBLE_COLUMN.defaultState(), false);
        int down = BlockProps.DRAG.with(BlockIds.BUBBLE_COLUMN.defaultState(), true);
        int[] palette = {
            up, up, down, down, BlockIds.WATER.defaultState(), BlockIds.STONE.defaultState(), BlockIds.AIR.defaultState()
        };
        try (var f = new JavaTickFixture()) {
            Random random = new Random(23);
            WorldStageBuilder builder = new WorldStageBuilder();
            BubbleColumnData empty = null;
            int hits = 0, misses = 0;
            for (int world = 0; world < 40; world++) {
                for (int x = -3; x <= 3; x++)
                    for (int y = 64; y <= 70; y++)
                        for (int z = -3; z <= 3; z++)
                            f.player.compensatedWorld.updateBlock(
                                    new BlockPos(x, y, z),
                                    random.nextInt(3) == 0
                                            ? BlockIds.AIR.defaultState()
                                            : palette[random.nextInt(palette.length)]);
                for (int move = 0; move < 200; move++) {
                    Vec3 from = new Vec3(random.nextDouble() * 6 - 3, 64 + random.nextDouble() * 5, random.nextDouble() * 6 - 3);
                    Vec3 delta = random.nextInt(8) == 0
                            ? Vec3.ZERO
                            : new Vec3(random.nextGaussian() * 0.6, random.nextGaussian() * 0.6, random.nextGaussian() * 0.6);
                    Vec3 to = from.add(delta);
                    SimulationContext context = context(from, random.nextBoolean() ? delta : delta.multiply(-1, 1, 2));
                    BubbleColumnData expected = reference(f.player, to, context);
                    BubbleColumnData actual = (BubbleColumnData) scan.invoke(builder, f.player, to, context);
                    assertEquals(expected, actual, "world " + world + " move " + move);
                    if (actual.hasBubbleColumn()) hits++;
                    else {
                        misses++;
                        if (empty == null) empty = actual;
                        assertSame(empty, actual);
                    }
                }
            }
            assertTrue(hits > 1000 && misses > 200, "hits=" + hits + " misses=" + misses);
        }
    }
}
