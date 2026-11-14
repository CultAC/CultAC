package ac.cult.blocksim.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.StubWorldView;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.BlockEntityData;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.Offsets;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Kept offset shapes equal a fresh build, field for field, for every state and every offset index. */
final class OffsetShapeTableTest {
    private static final DataTables DATA = DataTables.defaults();

    static final class View implements StubWorldView {
        @Override public int stateAt(BlockPos pos) { throw new IllegalStateException("Shape reads no neighbor"); }
        @Override public boolean isLoaded(BlockPos pos) { return true; }
        @Override public boolean isSectionEmpty(BlockPos pos) { return false; }
        @Override public int minY() { return -64; }
        @Override public int height() { return 384; }
        @Override public boolean isWithinBorder(BlockPos pos) { return true; }
        @Override public boolean creakingActiveAt(BlockPos pos) { return false; }
        @Override public boolean waterEvaporatesAt(BlockPos pos) { return false; }
        @Override public BlockEntityData blockEntityAt(BlockPos pos) { return null; }
        @Override public VoxelShape movingPistonCollisionAt(BlockPos pos) { return Shapes.empty(); }
    }

    private static SimLevel level(BehaviorRegistry behaviors) { return new SimLevel(new View(), DATA.registry(), behaviors); }

    // Reference: the offset parsed and computed on every read.
    private static double[] baseOffset(BlockDefinition block, BlockPos pos) {
        String type = block.bindings().get("offsetType");
        if ("NONE".equals(type)) return new double[] {0, 0, 0};
        if (!"XZ".equals(type) && !"XYZ".equals(type)) throw new IllegalArgumentException("Unbound offset type for " + block.key());
        long seed = Offsets.seed(pos.x(), 0, pos.z());
        float maxHorizontal = Float.parseFloat(block.bindings().get("getMaxHorizontalOffset"));
        double x = Math.max(-maxHorizontal, Math.min(maxHorizontal, ((float) (seed & 15L) / 15.0F - 0.5) * 0.5));
        double z = Math.max(-maxHorizontal, Math.min(maxHorizontal, ((float) (seed >> 8 & 15L) / 15.0F - 0.5) * 0.5));
        double y = "XYZ".equals(type) ? ((float) (seed >> 4 & 15L) / 15.0F - 1.0) * Float.parseFloat(block.bindings().get("getMaxVerticalOffset")) : 0;
        return new double[] {x, y, z};
    }

    // Reference: the pointed dripstone shape built on every read.
    private static VoxelShape baseSpeleothem(int state, BlockPos pos) {
        var registry = DATA.registry();
        String thickness = registry.value(state, "thickness");
        int width = switch (thickness) { case "tip", "tip_merge" -> 6; case "frustum" -> 8; case "middle" -> 10; case "base" -> 12; default -> throw new IllegalStateException(thickness); };
        double minY = 0, maxY = 1;
        if (thickness.equals("tip")) {
            if (Direction.valueOf(registry.value(state, "vertical_direction").toUpperCase(Locale.ROOT)) == Direction.DOWN) minY = 5.0 / 16; else maxY = 11.0 / 16;
        }
        var offset = baseOffset(registry.block(state), pos);
        return Shapes.create((8.0 - width / 2.0) / 16, minY, (8.0 - width / 2.0) / 16,
            (8.0 + width / 2.0) / 16, maxY, (8.0 + width / 2.0) / 16).move(offset[0], offset[1], offset[2]);
    }

    // Reference: the bamboo shape built on every read.
    private static VoxelShape baseBamboo(int state, BlockPos pos) {
        var offset = baseOffset(DATA.registry().block(state), pos);
        return Shapes.create(6.5 / 16, 0, 6.5 / 16, 9.5 / 16, 1, 9.5 / 16).move(offset[0], offset[1], offset[2]);
    }

    /** Two positions per offset index: one near the origin, one past the int overflow of the seed's multiplies. */
    private static List<List<BlockPos>> positions(long mask) {
        int count = mask == 0 ? 1 : Long.bitCount(mask) == 8 ? 256 : 4096;
        List<List<BlockPos>> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(new ArrayList<>());
        int[][] origins = {{0, 0}, {-29_999_000, 29_998_000}};
        for (int[] origin : origins) {
            int found = 0;
            for (int r = 0; found < count; r++) {
                if (r > 4096) throw new AssertionError("offset index not reached");
                for (int dx = -r; dx <= r && found < count; dx++) for (int dz = -r; dz <= r && found < count; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = origin[0] + dx, z = origin[1] + dz;
                    long bits = Offsets.seed(x, 0, z) & mask;
                    int index = mask == 0xF0FL ? (int) (bits & 0xF | bits >> 4 & 0xF0) : (int) bits;
                    var list = result.get(index);
                    if (list.size() < (origin == origins[0] ? 1 : 2)) { list.add(new BlockPos(x, 17 - r % 40, z)); found++; }
                }
            }
        }
        return result;
    }

    private static long mask(BlockDefinition block) {
        return switch (block.bindings().get("offsetType")) { case "NONE" -> 0; case "XZ" -> 0xF0FL; case "XYZ" -> 0xFFFL; default -> throw new AssertionError(); };
    }

    @Test
    void everyOffsetStateAndIndexMatchesAFreshBuild() throws ReflectiveOperationException {
        var behaviors = new BehaviorRegistry(DATA);
        var level = level(behaviors);
        int blocks = 0, reads = 0;
        for (var block : DATA.registry().blocks()) {
            var behavior = behaviors.apply(block.defaultState());
            boolean speleothem = behavior instanceof SpeleothemBehavior;
            if (!speleothem && !(behavior instanceof BambooStalkBehavior)) continue;
            blocks++;
            var byIndex = positions(mask(block));
            for (int state = block.firstState(); state < block.firstState() + block.stateCount(); state++) {
                for (var sameIndex : byIndex) {
                    VoxelShape kept = null;
                    for (var pos : sameIndex) {
                        var expected = speleothem ? baseSpeleothem(state, pos) : baseBamboo(state, pos);
                        var actual = behavior.collisionShape(level, state, pos);
                        assertSameFields(expected, actual, block.key() + " " + state + " " + pos);
                        assertSameFields(expected, behavior.supportShape(level, state, pos), block.key() + " support " + pos);
                        if (kept != null) assertSame(kept, actual);
                        kept = actual;
                        reads++;
                    }
                }
            }
        }
        assertTrue(blocks >= 2, "speleothem and bamboo blocks");
        assertTrue(reads > 0);
    }

    @Test
    void everyOffsetTypeIndexesItsSeedBits() throws ReflectiveOperationException {
        var behaviors = new BehaviorRegistry(DATA);
        var level = level(behaviors);
        for (String type : List.of("NONE", "XZ", "XYZ")) {
            var block = DATA.registry().blocks().stream().filter(b -> type.equals(b.bindings().get("offsetType"))
                    && (!type.equals("XYZ") || b.bindings().containsKey("getMaxVerticalOffset"))).findFirst().orElseThrow();
            var table = new OffsetShapeTable((ignored, state) -> Shapes.create(0.25, 0, 0.25, 0.75, 0.5, 0.75));
            for (var sameIndex : positions(mask(block))) {
                VoxelShape kept = null;
                for (var pos : sameIndex) {
                    var offset = baseOffset(block, pos);
                    var expected = Shapes.create(0.25, 0, 0.25, 0.75, 0.5, 0.75).move(offset[0], offset[1], offset[2]);
                    var actual = table.get(level, block.defaultState(), pos);
                    assertSameFields(expected, actual, type + " " + pos);
                    if (kept != null) assertSame(kept, actual);
                    kept = actual;
                }
            }
        }
    }

    @Test
    void anUnboundTypeOrFailedBuildIsNeverKept() {
        var level = level(new BehaviorRegistry(DATA));
        var block = DATA.registry().block("minecraft:pointed_dripstone");
        int[] builds = {0};
        var table = new OffsetShapeTable((ignored, state) -> {
            if (builds[0]++ == 0) throw new IllegalStateException("first build fails");
            return Shapes.block();
        });
        var pos = new BlockPos(3, 4, 5);
        assertThrows(IllegalStateException.class, () -> table.get(level, block.defaultState(), pos));
        var kept = table.get(level, block.defaultState(), pos);
        assertSame(kept, table.get(level, block.defaultState(), pos));
        assertEquals(2, builds[0]);
    }

    @Test
    void anotherRegistryStartsAFreshTable() throws Exception {
        var behaviors = new BehaviorRegistry(DATA);
        var other = DataTables.load(DATA.version());
        int state = DATA.registry().block("minecraft:pointed_dripstone").defaultState();
        var pos = new BlockPos(1, 2, 3);
        var behavior = behaviors.apply(state);
        var first = behavior.collisionShape(level(behaviors), state, pos);
        var second = behavior.collisionShape(new SimLevel(new View(), other.registry(), behaviors), state, pos);
        assertTrue(first != second);
        assertSameFields(first, second, "other registry");
        assertSame(second, behavior.collisionShape(new SimLevel(new View(), other.registry(), behaviors), state, pos));
        assertNotNull(behavior.collisionShape(level(behaviors), state, pos));
    }

    private static void assertSameFields(VoxelShape expected, VoxelShape actual, String where) throws ReflectiveOperationException {
        for (Field field : VoxelShape.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object a = field.get(expected), b = field.get(actual);
            if (a instanceof double[][] x) {
                double[][] y = (double[][]) b;
                assertEquals(x.length, y.length, where);
                for (int axis = 0; axis < x.length; axis++) {
                    assertEquals(x[axis].length, y[axis].length, where);
                    for (int i = 0; i < x[axis].length; i++)
                        assertEquals(Double.doubleToRawLongBits(x[axis][i]), Double.doubleToRawLongBits(y[axis][i]), where + " axis " + axis);
                }
            } else if (a instanceof BitSet cells) {
                assertEquals(cells, b, where);
            } else {
                assertEquals(a, b, where + " " + field.getName());
            }
        }
    }
}
