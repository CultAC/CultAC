package ac.cult.blocksim;

import ac.cult.blocksim.behavior.BehaviorRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.engine.SimWorldView;
import ac.cult.blocksim.engine.Vec3;
import ac.cult.blocksim.interaction.BlockHit;
import ac.cult.blocksim.interaction.Hand;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlacementContextTest {
    private static final DataTables DATA;
    private static final ItemRegistry ITEMS;
    private static final BlockPos POS = new BlockPos(0, 64, 0);
    static {
        try { DATA = DataTables.load("26.3"); ITEMS = new ItemRegistry(DATA); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static int state(String key) { return DATA.registry().block("minecraft:" + key).defaultState(); }
    private static int with(int state, String property, String value) { return DATA.registry().with(state, property, value); }

    @Test void bottomSlabSideClicksUseAStrictHalfBoundary() {
        int slab = state("oak_slab");
        var equal = context(Map.of(POS, slab), Direction.EAST, 0.5, "oak_slab", 0, 0);
        var above = context(Map.of(POS, slab), Direction.EAST, Math.nextUp(64.5) - 64, "oak_slab", 0, 0);
        assertFalse(equal.replacingClicked());
        assertEquals(POS.relative(Direction.EAST), equal.clickedPos());
        assertTrue(above.replacingClicked());
        assertEquals(POS, above.clickedPos());
        int merged = above.level().behavior(slab).placementState(DATA.registry().block(slab), above);
        assertEquals("double", DATA.registry().value(merged, "type"));
        assertEquals("false", DATA.registry().value(merged, "waterlogged"));
    }

    @Test void adjacentSlabsCanMergeDespiteTheFaceOfTheOriginallyClickedBlock() {
        int slab = with(state("oak_slab"), "type", "top");
        var context = context(Map.of(POS, state("stone"), POS.relative(Direction.EAST), slab), Direction.EAST, 0.75, "oak_slab", 0, 0);
        assertFalse(context.replacingClicked());
        assertTrue(context.canPlace());
        assertEquals("double", DATA.registry().value(context.level().behavior(slab).placementState(DATA.registry().block(slab), context), "type"));
    }

    @Test void clickedFaceOppositeMovesToTheFrontOnlyWhenTheTargetIsAdjacent() {
        var replace = context(Map.of(), Direction.UP, 1, "stone", 0, 0);
        var adjacent = context(Map.of(POS, state("stone")), Direction.UP, 1, "stone", 0, 0);
        assertEquals(Direction.SOUTH, replace.nearestLookingDirections()[0]);
        assertEquals(Direction.DOWN, adjacent.nearestLookingDirections()[0]);
        assertEquals(Direction.SOUTH, adjacent.nearestLookingDirection());
        assertEquals(Direction.DOWN, adjacent.nearestLookingVerticalDirection());
    }

    @Test void componentStatePropertiesIgnoreUnknownKeysAndInvalidValues() {
        int slab = state("oak_slab");
        assertEquals(slab, DATA.registry().withIfValid(slab, "type", "unrecognized"));
        assertEquals(slab, DATA.registry().withIfValid(slab, "unknown_property", "true"));
        assertEquals(with(slab, "type", "top"), DATA.registry().withIfValid(slab, "type", "top"));
        assertThrows(IllegalArgumentException.class, () -> DATA.registry().with(slab, "type", "unrecognized"));
    }

    private static PlacementContext context(Map<BlockPos, Integer> states, Direction face, double cursorY, String item, float yaw, float pitch) {
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
        var level = new SimLevel(view, DATA.registry(), new BehaviorRegistry(DATA));
        var stack = ITEMS.stack("minecraft:" + item, 32);
        var player = new SimPlayer(new SimPlayer.State(new Vec3(0, 64, 0), yaw, pitch, false, false, true, false, SimPlayer.GameMode.SURVIVAL), stack, ITEMS.empty());
        return new PlacementContext(level, player, Hand.MAIN_HAND, stack, new BlockHit(POS, face, new Vec3(0.5, 64 + cursorY, 0.5), false));
    }
}
