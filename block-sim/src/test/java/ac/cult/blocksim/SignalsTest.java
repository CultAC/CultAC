package ac.cult.blocksim;

import ac.cult.blocksim.behavior.BehaviorRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.Signals;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimWorldView;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SignalsTest {
    private static final DataTables DATA;
    private static final BlockPos POS = new BlockPos(0, 64, 0);
    static {
        try { DATA = DataTables.load("26.3"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static int state(String block) { return DATA.registry().block("minecraft:" + block).defaultState(); }
    private static int with(int state, String property, String value) { return DATA.registry().with(state, property, value); }

    @Test void attachedSourcesSendDirectPowerTowardTheirAttachmentOnly() {
        var world = new World(); var level = level(world);
        for (String block : new String[]{"stone_button", "lever"}) {
            for (String face : new String[]{"floor", "ceiling", "wall"}) {
                for (Direction facing : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
                    int source = with(with(with(state(block), "face", face), "facing", facing.name().toLowerCase(java.util.Locale.ROOT)), "powered", "true");
                    world.states.put(POS, source);
                    Direction attachment = face.equals("floor") ? Direction.UP : face.equals("ceiling") ? Direction.DOWN : facing;
                    for (Direction query : Direction.values()) {
                        assertEquals(15, level.behavior(source).signal(level, source, POS, query));
                        assertEquals(query == attachment ? 15 : 0, level.behavior(source).directSignal(level, source, POS, query));
                    }
                    int off = with(source, "powered", "false");
                    for (Direction query : Direction.values()) {
                        assertEquals(0, level.behavior(off).signal(level, off, POS, query));
                        assertEquals(0, level.behavior(off).directSignal(level, off, POS, query));
                    }
                }
            }
        }
    }

    @Test void torchDirectionRulesDifferBetweenStandingAndWallVariants() {
        var world = new World(); var level = level(world);
        int standing = state("redstone_torch");
        int wall = with(state("redstone_wall_torch"), "facing", "east");
        for (Direction query : Direction.values()) {
            assertEquals(query == Direction.UP ? 0 : 15, level.behavior(standing).signal(level, standing, POS, query));
            assertEquals(query == Direction.EAST ? 0 : 15, level.behavior(wall).signal(level, wall, POS, query));
            assertEquals(query == Direction.DOWN ? 15 : 0, level.behavior(standing).directSignal(level, standing, POS, query));
            assertEquals(query == Direction.DOWN ? 15 : 0, level.behavior(wall).directSignal(level, wall, POS, query));
        }
    }

    @Test void weakPowerDoesNotPropagateThroughSolidBlocksButDirectPowerDoes() {
        var world = new World(); var level = level(world); var signals = new Signals(level);
        world.states.put(POS, state("stone"));
        var west = POS.relative(Direction.WEST);
        world.states.put(west, state("redstone_block"));
        assertEquals(0, signals.signal(POS, Direction.EAST));
        world.states.put(west, with(with(with(state("lever"), "face", "wall"), "facing", "west"), "powered", "true"));
        assertEquals(15, signals.signal(POS, Direction.EAST));
        assertTrue(signals.hasNeighborSignal(POS.relative(Direction.EAST)));
        assertTrue(level.writes().isEmpty(), "Querying signals must not run server redstone updates");
    }

    @Test void weightedPlateAndCalibratedSensorPreserveAllSixteenPowerValues() {
        var world = new World(); var level = level(world);
        for (int power = 0; power <= 15; power++) {
            int plate = with(state("light_weighted_pressure_plate"), "power", Integer.toString(power));
            int sensor = with(with(state("calibrated_sculk_sensor"), "power", Integer.toString(power)), "facing", "north");
            for (Direction query : Direction.values()) {
                assertEquals(power, level.behavior(plate).signal(level, plate, POS, query));
                assertEquals(query == Direction.UP ? power : 0, level.behavior(plate).directSignal(level, plate, POS, query));
                assertEquals(query == Direction.NORTH ? 0 : power, level.behavior(sensor).signal(level, sensor, POS, query));
                assertEquals(query == Direction.UP ? power : 0, level.behavior(sensor).directSignal(level, sensor, POS, query));
            }
        }
    }

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
