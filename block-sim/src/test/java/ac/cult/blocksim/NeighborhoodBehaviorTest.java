package ac.cult.blocksim;

import ac.cult.blocksim.behavior.BehaviorRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimWorldView;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NeighborhoodBehaviorTest {
    private static final DataTables DATA;
    private static final BlockPos POS = new BlockPos(0, 64, 0);
    static {
        try { DATA = DataTables.load("26.3"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static int state(String key) { return DATA.registry().block("minecraft:" + key).defaultState(); }
    private static int with(int state, String key, String value) { return DATA.registry().with(state, key, value); }

    @Test void removingATripwireConnectionKeepsPoweredAndDisarmedState() {
        int wire = with(with(with(state("tripwire"), "west", "true"), "powered", "true"), "disarmed", "true");
        var level = level(Map.of(POS, wire));
        int updated = level.behavior(wire).updateShape(level, wire, POS, Direction.WEST, POS.relative(Direction.WEST), state("air"));
        assertEquals(with(wire, "west", "false"), updated);
    }

    @Test void aMushroomFaceDoesNotReappearWhenItsNeighborIsRemoved() {
        int mushroom = with(state("red_mushroom_block"), "north", "false"); var level = level(Map.of(POS, mushroom));
        assertEquals(mushroom, level.behavior(mushroom).updateShape(level, mushroom, POS, Direction.NORTH, POS.relative(Direction.NORTH), state("air")));
    }

    @Test void copperChestUpdateCopiesItsPartnersOxidationAndPreservesRootProperties() {
        int chest = with(with(state("copper_chest"), "type", "left"), "waterlogged", "true");
        int partner = with(state("oxidized_copper_chest"), "type", "right");
        var level = level(Map.of(POS, chest, POS.relative(Direction.EAST), partner));
        int updated = level.behavior(chest).updateShape(level, chest, POS, Direction.EAST, POS.relative(Direction.EAST), partner);
        assertEquals("minecraft:oxidized_copper_chest", DATA.registry().block(updated).key());
        assertEquals("left", DATA.registry().value(updated, "type"));
        assertEquals("true", DATA.registry().value(updated, "waterlogged"));
    }

    @Test void propertyCopyRequiresTheNativePropertyTypeAndEntireAllowedValueSet() {
        int door = with(state("oak_door"), "facing", "east");
        int stairs = DATA.registry().withPropertiesOf(DATA.registry().block("minecraft:oak_stairs"), with(door, "half", "upper"));
        assertEquals("east", DATA.registry().value(stairs, "facing"));
        assertEquals("bottom", DATA.registry().value(stairs, "half"));
        assertEquals(state("observer"), DATA.registry().withPropertiesOf(DATA.registry().block("minecraft:observer"), door));
    }

    @Test void aMobHeadAboveANoteBlockTakesPriorityOverItsSupportingBlock() {
        int note = state("note_block"); var level = level(Map.of(POS, note, POS.relative(Direction.UP), state("skeleton_skull"), POS.relative(Direction.DOWN), state("stone")));
        int updated = level.behavior(note).updateShape(level, note, POS, Direction.DOWN, POS.relative(Direction.DOWN), state("stone"));
        assertEquals("skeleton", DATA.registry().value(updated, "instrument"));
        level = level(Map.of(POS, note, POS.relative(Direction.DOWN), state("skeleton_skull")));
        updated = level.behavior(note).updateShape(level, note, POS, Direction.DOWN, POS.relative(Direction.DOWN), state("skeleton_skull"));
        assertEquals("harp", DATA.registry().value(updated, "instrument"));
    }

    @Test void concretePowderSolidifiesBesideWaterButNotWaterBelowIt() {
        int powder = state("blue_concrete_powder"), water = state("water");
        var level = level(Map.of(POS, powder, POS.relative(Direction.NORTH), water));
        assertEquals(state("blue_concrete"), level.behavior(powder).updateShape(level, powder, POS, Direction.EAST, POS.relative(Direction.EAST), state("air")));
        level = level(Map.of(POS, powder, POS.relative(Direction.DOWN), water));
        assertEquals(powder, level.behavior(powder).updateShape(level, powder, POS, Direction.DOWN, POS.relative(Direction.DOWN), water));
    }

    @Test void unsupportedHangingMossStillUpdatesItsTipOnTheClient() {
        int moss = with(state("pale_hanging_moss"), "tip", "false"); var level = level(Map.of(POS, moss));
        assertFalse(level.behavior(moss).canSurvive(level, moss, POS));
        assertEquals(with(moss, "tip", "true"), level.behavior(moss).updateShape(level, moss, POS, Direction.UP, POS.relative(Direction.UP), state("air")));
    }

    @Test void sulfurRequiresSourceWaterAboveEvenWhenFlowingWaterHasFullAmount() {
        int sulfur = with(state("potent_sulfur"), "potent_sulfur_state", "wet");
        int flowing = with(state("water"), "level", "8"); var level = level(Map.of(POS, sulfur, POS.relative(Direction.UP), flowing));
        assertEquals(8, level.fluidAt(POS.relative(Direction.UP)).amount());
        assertFalse(level.fluidAt(POS.relative(Direction.UP)).isSource());
        assertEquals(state("potent_sulfur"), level.behavior(sulfur).updateShape(level, sulfur, POS, Direction.NORTH, POS.relative(Direction.NORTH), state("air")));
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
