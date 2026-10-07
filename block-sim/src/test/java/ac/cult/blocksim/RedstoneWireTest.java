package ac.cult.blocksim;

import ac.cult.blocksim.behavior.BehaviorRegistry;
import ac.cult.blocksim.behavior.RedstoneWireBehavior;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimWorldView;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RedstoneWireTest {
    private static final DataTables DATA;
    private static final BlockPos POS = new BlockPos(0, 64, 0);
    static {
        try { DATA = DataTables.load("26.3"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static int state(String block) { return DATA.registry().block("minecraft:" + block).defaultState(); }
    private static int with(int state, String property, String value) { return DATA.registry().with(state, property, value); }

    @Test void isolatedDotStaysDotAndIsolatedCrossStaysCross() {
        var world = new World(); var level = level(world); var wire = wire(level);
        int dot = with(state("redstone_wire"), "power", "15");
        int cross = dot;
        for (String property : new String[]{"north", "east", "south", "west"}) cross = with(cross, property, "side");
        assertEquals(dot, wire.connectionState(level, dot, POS));
        assertEquals(cross, wire.connectionState(level, cross, POS));
        assertEquals(0, wire.signal(level, dot, POS, Direction.EAST));
        assertEquals(15, wire.signal(level, dot, POS, Direction.UP));
        assertEquals(15, wire.signal(level, cross, POS, Direction.EAST));
        assertEquals(0, wire.signal(level, cross, POS, Direction.DOWN));
    }

    @Test void oneAdjacentWireCreatesAStraightLineForSignalReads() {
        var world = new World(); var level = level(world); var wire = wire(level);
        int dot = with(state("redstone_wire"), "power", "7");
        world.states.put(POS.relative(Direction.NORTH), state("redstone_wire"));
        int connected = wire.connectionState(level, dot, POS);
        assertEquals("side", DATA.registry().value(connected, "north"));
        assertEquals("side", DATA.registry().value(connected, "south"));
        assertEquals("none", DATA.registry().value(connected, "east"));
        assertEquals("none", DATA.registry().value(connected, "west"));
        assertEquals(7, wire.signal(level, dot, POS, Direction.NORTH));
        assertEquals(7, wire.signal(level, dot, POS, Direction.SOUTH));
        assertEquals(0, wire.signal(level, dot, POS, Direction.EAST));
    }

    @Test void upwardWireNeedsSupportAndSpaceAboveTheOrigin() {
        var world = new World(); var level = level(world); var wire = wire(level);
        BlockPos north = POS.relative(Direction.NORTH);
        world.states.put(north, state("stone"));
        world.states.put(north.relative(Direction.UP), state("redstone_wire"));
        int dot = state("redstone_wire");
        assertEquals("up", DATA.registry().value(wire.connectionState(level, dot, POS), "north"));
        world.states.put(POS.relative(Direction.UP), state("stone"));
        assertEquals(dot, wire.connectionState(level, dot, POS));
    }

    @Test void supportLossReturnsAirButClientUpdateOrDestroyDoesNotRemoveIt() {
        var world = new World(); var level = level(world); var wire = wire(level);
        var below = POS.relative(Direction.DOWN);
        world.states.put(below, state("stone")); world.states.put(POS, state("redstone_wire"));
        assertTrue(wire.canSurvive(level, state("redstone_wire"), POS));
        assertEquals(state("air"), wire.updateShape(level, state("redstone_wire"), POS, Direction.DOWN, below, state("air")));
        assertTrue(level.setBlock(below, state("air"), 11));
        assertEquals(state("redstone_wire"), level.stateAt(POS));
        assertEquals(1, level.writes().size());
    }

    private static RedstoneWireBehavior wire(SimLevel level) { return (RedstoneWireBehavior) level.behavior(state("redstone_wire")); }
    private static SimLevel level(World world) { return new SimLevel(world, DATA.registry(), new BehaviorRegistry(DATA)); }
    private static final class World implements SimWorldView {
        final Map<BlockPos, Integer> states = new HashMap<>();
        public int stateAt(BlockPos pos) { return states.getOrDefault(pos, state("air")); }
        public boolean isLoaded(BlockPos pos) { return true; }
        public boolean isSectionEmpty(BlockPos pos) { return false; }
        public int minY() { return -64; }
        public int height() { return 384; }
        public boolean isWithinBorder(ac.cult.blocksim.engine.BlockPos pos) { return true; }
        public boolean creakingActiveAt(ac.cult.blocksim.engine.BlockPos pos) { return false; }
        public boolean waterEvaporatesAt(ac.cult.blocksim.engine.BlockPos pos) { return false; }
        public ac.cult.blocksim.engine.BlockEntityData blockEntityAt(ac.cult.blocksim.engine.BlockPos pos) { return null; }
    }
}
