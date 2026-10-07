package ac.cult.blocksim;

import ac.cult.blocksim.behavior.BehaviorRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimWorldView;
import ac.cult.blocksim.engine.Vec3;
import ac.cult.blocksim.interaction.BlockHit;
import ac.cult.blocksim.interaction.Hand;
import ac.cult.blocksim.interaction.InteractionKind;
import ac.cult.blocksim.interaction.UseContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoorTest {
    private static final DataTables DATA;
    private static final BlockPos POS = new BlockPos(0, 64, 0), ABOVE = POS.relative(Direction.UP);
    static {
        try { DATA = DataTables.load("26.3"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static int state(String key) { return DATA.registry().block("minecraft:" + key).defaultState(); }
    private static int with(int state, String property, String value) { return DATA.registry().with(state, property, value); }

    @Test void aDoorCopiesAnOppositeHalfEvenWhenItUsesAnotherDoorBlock() {
        int lower = state("oak_door"), upper = with(with(state("iron_door"), "half", "upper"), "open", "true");
        var level = level(Map.of(POS, lower, ABOVE, upper));
        assertEquals(with(upper, "half", "lower"), level.behavior(lower).updateShape(level, lower, POS, Direction.UP, ABOVE, upper));
        int sameHalf = with(upper, "half", "lower");
        assertEquals(state("air"), level.behavior(lower).updateShape(level, lower, POS, Direction.UP, ABOVE, sameHalf));
    }

    @Test void openingALowerHalfWritesBothHalvesAndRetainsEachOriginal() {
        int lower = state("oak_door"), upper = with(lower, "half", "upper");
        var level = level(Map.of(POS, lower, ABOVE, upper, POS.relative(Direction.DOWN), state("stone")));
        var result = level.behavior(lower).useWithoutItem(lower, new UseContext(level, null, Hand.MAIN_HAND, null,
            new BlockHit(POS, Direction.NORTH, new Vec3(0.5, 64.5, 0), false)));
        assertEquals(InteractionKind.SUCCESS, result.kind());
        assertEquals(2, level.writes().size());
        assertEquals(POS, level.writes().getFirst().pos());
        assertEquals(ABOVE, level.writes().getLast().pos());
        assertEquals("true", DATA.registry().value(level.stateAt(POS), "open"));
        assertEquals("true", DATA.registry().value(level.stateAt(ABOVE), "open"));
        assertEquals(lower, level.retainedStates().get(POS));
        assertEquals(upper, level.retainedStates().get(ABOVE));
    }

    @Test void anIronDoorDoesNotOpenByHand() {
        int iron = state("iron_door"); var level = level(Map.of(POS, iron));
        assertEquals(InteractionKind.PASS, level.behavior(iron).useWithoutItem(iron, new UseContext(level, null, Hand.MAIN_HAND, null,
            new BlockHit(POS, Direction.UP, new Vec3(0.5, 65, 0.5), false))).kind());
        assertTrue(level.writes().isEmpty());
    }

    @Test void placingTheUpperHalfKeepsTheRootWriteFirst() {
        int lower = state("oak_door"); var level = level(Map.of(POS.relative(Direction.DOWN), state("stone")));
        assertTrue(level.setBlock(POS, lower, 11));
        level.behavior(lower).setPlacedBy(level, lower, POS, null, null);
        assertEquals(2, level.writes().size());
        assertEquals(POS, level.writes().getFirst().pos());
        assertEquals(ABOVE, level.writes().getLast().pos());
        assertEquals(with(lower, "half", "upper"), level.stateAt(ABOVE));
    }

    private static SimLevel level(Map<BlockPos, Integer> states) {
        SimWorldView view = new SimWorldView() {
            public int stateAt(BlockPos pos) { return states.getOrDefault(pos, state("air")); }
            public boolean isLoaded(BlockPos pos) { return true; }
            public boolean isSectionEmpty(BlockPos pos) { return false; }
            public int minY() { return -64; }
            public int height() { return 384; }
            public boolean isWithinBorder(ac.cult.blocksim.engine.BlockPos pos) { return true; }
            public boolean creakingActiveAt(ac.cult.blocksim.engine.BlockPos pos) { return false; }
            public boolean waterEvaporatesAt(ac.cult.blocksim.engine.BlockPos pos) { return false; }
        public ac.cult.blocksim.engine.BlockEntityData blockEntityAt(ac.cult.blocksim.engine.BlockPos pos) { return null; }
        };
        return new SimLevel(view, DATA.registry(), new BehaviorRegistry(DATA));
    }
}
