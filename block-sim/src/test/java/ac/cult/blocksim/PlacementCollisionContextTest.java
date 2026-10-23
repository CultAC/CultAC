package ac.cult.blocksim;

import ac.cult.blocksim.behavior.ConsumableBehavior;
import ac.cult.blocksim.data.*;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** BlockItem#canPlace uses CollisionContext#of the player before 1.21.5 and placementContext after it. */
class PlacementCollisionContextTest {
    private static final DataTables DATA;
    private static final ItemRegistry ITEMS;
    private static final BlockPos GROUND = new BlockPos(0, 64, 0), TOP = GROUND.relative(Direction.UP);
    static {
        try { DATA = DataTables.load("26.3"); ITEMS = new ItemRegistry(DATA); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @Test void legacyScaffoldingIsObstructedByItsBottomSlabAtThePlayersFeet() {
        var kinds = new ArrayList<PlacementObstruction.CollisionContext>();
        assertTrue(place(BlockSimClientVersion.V1_21_4, kinds).isEmpty());
        assertEquals(List.of(PlacementObstruction.CollisionContext.ENTITY), kinds);
        kinds.clear();
        assertEquals(List.of(TOP), place(BlockSimClientVersion.V26_3, kinds));
        assertEquals(List.of(PlacementObstruction.CollisionContext.PLACEMENT), kinds);
    }

    /** Places distance=1, bottom=true scaffolding into the cell of feet at Y65.05. */
    private static List<BlockPos> place(BlockSimClientVersion version, List<PlacementObstruction.CollisionContext> kinds) {
        PlacementObstruction feet = (context, state, kind) -> {
            kinds.add(kind);
            var collision = new EntityCollisionContext(TOP.y() + .05, false, 0, false,
                kind == PlacementObstruction.CollisionContext.PLACEMENT);
            return context.level().behavior(state).collisionShape(context.level(), state, context.clickedPos(), collision).isEmpty();
        };
        var simulator = BlockSimulator.create(DATA, ITEMS, InteractionRegistries.defaults(), feet,
            new ConsumableBehavior(context -> {}));
        var owner = new SimPlayer(new SimPlayer.State(new Vec3(.5, 65.05, .5), 0, 0, false, false, true, false,
            SimPlayer.GameMode.SURVIVAL), ITEMS.stack("minecraft:scaffolding", 2), ITEMS.empty());
        var action = new SimAction.UseOn(Hand.MAIN_HAND, new BlockHit(GROUND, Direction.UP, new Vec3(.5, 65, .5), false));
        var result = simulator.simulate(action, new SimInput(world(), owner, new SimCooldowns(0, Map.of()), null,
            BreakSession.State.initial(ITEMS.empty()), HolderSets.Overlay.EMPTY, version));
        return result.writes().stream().map(BlockWrite::pos).toList();
    }

    private static SimWorldView world() {
        var registry = DATA.registry();
        int bars = registry.block("minecraft:iron_bars").defaultState();
        int scaffolding = registry.with(registry.block("minecraft:scaffolding").defaultState(), "distance", "0");
        int air = registry.block("minecraft:air").defaultState();
        var neighbor = TOP.relative(Direction.EAST);
        return new StubWorldView() {
            public int stateAt(BlockPos pos) { return pos.equals(GROUND) ? bars : pos.equals(neighbor) ? scaffolding : air; }
            public boolean isLoaded(BlockPos pos) { return true; }
            public boolean isSectionEmpty(BlockPos pos) { return pos.y() >> 4 != GROUND.y() >> 4; }
            public int minY() { return -64; }
            public int height() { return 384; }
            public boolean isWithinBorder(BlockPos pos) { return true; }
            public boolean creakingActiveAt(BlockPos pos) { return false; }
            public boolean waterEvaporatesAt(BlockPos pos) { return false; }
            public BlockEntityData blockEntityAt(BlockPos pos) { return null; }
        };
    }
}
