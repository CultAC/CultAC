package ac.cult.blocksim;

import ac.cult.blocksim.behavior.BlockBehavior;
import ac.cult.blocksim.behavior.BehaviorRegistry;
import ac.cult.blocksim.behavior.SnowyBehavior;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.Signals;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimWorldView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimLevelTest {
    private static final DataTables DATA;
    static {
        try { DATA = DataTables.load("26.3"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private int state(String block) { return DATA.registry().block("minecraft:" + block).defaultState(); }

    @Test void snowWritesBeforeDirtCascadeButDirtRetainsBeforeSnow() {
        var ground = new BlockPos(0, 64, 0);
        var snow = ground.relative(Direction.UP);
        World world = new World();
        world.states.put(ground, state("grass_block"));
        var snowy = new SnowyBehavior(DATA.tags().get("block:minecraft:snow"));
        var defaults = new BlockBehavior();
        SimLevel level = new SimLevel(world, DATA.registry(), id -> DATA.registry().sameBlock(id, state("grass_block")) ? snowy : defaults);
        assertTrue(level.setBlock(snow, state("snow_block"), 11));
        assertEquals(List.of(snow, ground), level.writes().stream().map(w -> w.pos()).toList());
        assertEquals(List.of(ground, snow), new ArrayList<>(level.retainedStates().keySet()));
        assertEquals("true", DATA.registry().value(level.stateAt(ground), "snowy"));
        assertEquals(state("grass_block"), level.retainedStates().get(ground));
        assertEquals(state("air"), level.retainedStates().get(snow));
    }

    @Test void shapeUpdateToAirDoesNotDestroyOnClient() {
        var pos = new BlockPos(0, 64, 0);
        var torch = pos.relative(Direction.UP);
        World world = new World();
        world.states.put(torch, state("torch"));
        var behavior = new BlockBehavior() {
            @Override public int updateShape(SimLevel level, int state, BlockPos p, Direction d, BlockPos n, int neighbor) { return SimLevelTest.this.state("air"); }
        };
        SimLevel level = new SimLevel(world, DATA.registry(), ignored -> behavior);
        assertTrue(level.setBlock(pos, state("stone"), 3));
        assertEquals(state("torch"), level.stateAt(torch));
        assertEquals(1, level.writes().size());
    }

    @Test void updateOrderFlagsDepthAndNoOpResultsMatchClientBranches() {
        World world = new World();
        var pos = new BlockPos(0, 64, 0);
        List<Direction> updates = new ArrayList<>();
        List<String> indirect = new ArrayList<>();
        var behavior = new BlockBehavior() {
            @Override public int updateShape(SimLevel level, int state, BlockPos p, Direction d, BlockPos n, int neighbor) { updates.add(d); return state; }
            @Override public void updateIndirectShapes(SimLevel level, int state, BlockPos p, int flags, int limit) { indirect.add(state + ":" + flags + ":" + limit); }
        };
        SimLevel level = new SimLevel(world, DATA.registry(), ignored -> behavior);
        assertFalse(level.setBlock(pos, state("air"), 3));
        assertTrue(level.setBlock(pos, state("stone"), 35, 2));
        assertEquals(List.of(Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH, Direction.UP, Direction.DOWN), updates);
        assertEquals(List.of(state("air") + ":2:1", state("stone") + ":2:1"), indirect);
        assertFalse(level.setBlock(pos, state("stone"), 3));
        assertEquals(1, level.writes().size());
        updates.clear(); indirect.clear();
        assertTrue(level.setBlock(pos, state("dirt"), 19));
        assertTrue(updates.isEmpty()); assertTrue(indirect.isEmpty());
        assertEquals(state("air"), level.retainedStates().get(pos));
        assertFalse(level.setBlock(new BlockPos(0, 320, 0), state("stone"), 3));
        int beyondValidChunks = (DATA.registry().maxValidChunkCoordinate() + 1) << 4;
        assertFalse(level.setBlock(new BlockPos(beyondValidChunks, 64, 0), state("stone"), 3));
    }

    @Test void signalsReadVisibleStateWithoutTriggeringRedstoneUpdates() {
        World world = new World();
        var target = new BlockPos(0, 64, 0);
        var source = target.relative(Direction.WEST);
        var powered = new BehaviorRegistry(DATA).apply(state("redstone_block"));
        var defaults = new BlockBehavior();
        SimLevel level = new SimLevel(world, DATA.registry(), id -> DATA.registry().sameBlock(id, state("redstone_block")) ? powered : defaults);
        Signals signals = new Signals(level);
        assertFalse(signals.hasNeighborSignal(target));
        assertTrue(level.setBlock(source, state("redstone_block"), 3));
        assertTrue(signals.hasNeighborSignal(target));
        assertEquals(1, level.writes().size());
        assertEquals(0, signals.directSignal(source, Direction.WEST));
    }

    @Test void airVariantsKeepTheEmptySectionEarlyReturn() {
        var world = new World(); var pos = new BlockPos(0, 64, 0);
        world.states.put(pos, state("void_air"));
        SimLevel level = new SimLevel(world, DATA.registry(), ignored -> new BlockBehavior());
        assertFalse(level.setBlock(pos, state("air"), 19));
        assertEquals(state("void_air"), level.stateAt(pos));
        assertTrue(level.setBlock(pos.relative(Direction.EAST), state("stone"), 19));
        assertTrue(level.setBlock(pos, state("air"), 19));
        assertEquals(state("air"), level.stateAt(pos));
    }

    @Test void nestedRewriteRetainsTheStateCapturedByFirstCompletedClientSetBlock() {
        World world = new World();
        var pos = new BlockPos(0, 64, 0);
        var trigger = pos.relative(Direction.WEST);
        var behavior = new BlockBehavior() {
            private boolean rewritten;
            @Override public int updateShape(SimLevel level, int state, BlockPos p, Direction d, BlockPos n, int neighbor) {
                if (p.equals(trigger) && !rewritten) {
                    rewritten = true;
                    level.setBlock(pos, SimLevelTest.this.state("dirt"), 19);
                }
                return state;
            }
        };
        SimLevel level = new SimLevel(world, DATA.registry(), ignored -> behavior);
        assertTrue(level.setBlock(pos, state("stone"), 3));
        assertEquals(2, level.writes().size());
        assertEquals(state("air"), level.writes().get(0).oldState());
        assertEquals(state("stone"), level.writes().get(1).oldState());
        assertEquals(state("dirt"), level.stateAt(pos));
        assertEquals(Map.of(pos, state("stone")), level.retainedStates());
    }

    @Test void chainedUpdateCapCountsQueuedChildrenAndResetsAfterEachTopLevelUpdate() {
        World world = new World();
        var source = new BlockPos(0, 64, 0);
        var first = source.relative(Direction.WEST);
        var child1 = new BlockPos(10, 64, 0);
        var child2 = new BlockPos(20, 64, 0);
        List<BlockPos> calls = new ArrayList<>();
        var behavior = new BlockBehavior() {
            @Override public int updateShape(SimLevel level, int state, BlockPos p, Direction d, BlockPos n, int neighbor) {
                calls.add(p);
                if (p.equals(first)) {
                    level.shapeUpdater().shapeUpdate(Direction.UP, neighbor, child1, source, 2, 0);
                    level.shapeUpdater().shapeUpdate(Direction.UP, neighbor, child2, source, 2, 0);
                }
                return state;
            }
        };
        SimLevel level = new SimLevel(world, DATA.registry(), ignored -> behavior, 2);
        assertTrue(level.setBlock(source, state("stone"), 3));
        assertEquals(List.of(first, child1, source.relative(Direction.EAST), source.relative(Direction.NORTH),
            source.relative(Direction.SOUTH), source.relative(Direction.DOWN), source.relative(Direction.UP)), calls);
    }

    private final class World implements SimWorldView {
        private final Map<BlockPos, Integer> states = new HashMap<>();
        public int stateAt(BlockPos pos) { return states.getOrDefault(pos, state("air")); }
        public boolean isLoaded(BlockPos pos) { return true; }
        public boolean isSectionEmpty(BlockPos pos) {
            return states.entrySet().stream().noneMatch(e -> (e.getKey().x() >> 4) == (pos.x() >> 4)
                && (e.getKey().y() >> 4) == (pos.y() >> 4) && (e.getKey().z() >> 4) == (pos.z() >> 4)
                && !DATA.registry().facts(e.getValue()).has(StateFacts.AIR));
        }
        public int minY() { return -64; }
        public int height() { return 384; }
        public boolean isWithinBorder(ac.cult.blocksim.engine.BlockPos pos) { return true; }
        public boolean creakingActiveAt(ac.cult.blocksim.engine.BlockPos pos) { return false; }
        public boolean waterEvaporatesAt(ac.cult.blocksim.engine.BlockPos pos) { return false; }
        public ac.cult.blocksim.engine.BlockEntityData blockEntityAt(ac.cult.blocksim.engine.BlockPos pos) { return null; }
    }
}
