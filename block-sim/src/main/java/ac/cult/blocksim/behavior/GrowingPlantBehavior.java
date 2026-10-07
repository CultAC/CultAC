package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimFluidState;
import ac.cult.blocksim.interaction.PlacementContext;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;
import java.util.Set;

/** One head/body mechanic. Kelp changes support and placement; cave vines preserve
 * berries during conversion. Growth direction and pairs come from vanilla bindings. */
public final class GrowingPlantBehavior extends BlockBehavior {
    public enum Part { HEAD, BODY }
    public enum Kind { PLAIN, KELP, CAVE_VINES }
    private final Part part;
    private final Kind kind;
    private final Set<String> cannotSupport, water;

    public GrowingPlantBehavior(DataTables data, Part part, Kind kind) {
        this.part = part; this.kind = kind;
        cannotSupport = data.tags().get("block:minecraft:cannot_support_kelp");
        water = data.tags().get("fluid:minecraft:water");
    }

    private static Direction growth(SimLevel level, int state) {
        return Direction.valueOf(level.registry().block(state).bindings().get("GrowingPlantBlock.growthDirection"));
    }
    private static BlockDefinition head(SimLevel level, int state) {
        return level.registry().block(level.registry().block(state).bindings().get("getHeadBlock"));
    }
    private static BlockDefinition body(SimLevel level, int state) {
        return level.registry().block(level.registry().block(state).bindings().get("getBodyBlock"));
    }
    private static boolean isPlant(SimLevel level, int state, int neighbor) {
        var block = level.registry().block(neighbor);
        return block == head(level, state) || block == body(level, state);
    }

    /** GrowingPlantHead/BodyBlock.isValidBonemealTarget; cave vines instead grow berries. */
    boolean isValidBonemealTarget(SimLevel level, int state, BlockPos pos) {
        if (kind == Kind.CAVE_VINES) return !bool(level, state, "berries");
        Direction direction = growth(level, state);
        var head = head(level, state);
        if (part == Part.BODY) {
            var body = body(level, state);
            pos = pos.relative(direction);
            while (level.registry().block(level.stateAt(pos)) == body) pos = pos.relative(direction);
            if (level.registry().block(level.stateAt(pos)) != head) return false;
        }
        var next = pos.relative(direction);
        if (next.y() < level.minY() || next.y() > level.maxY()) return false;
        int neighbor = level.stateAt(next);
        return kind == Kind.KELP ? level.registry().block(neighbor).key().equals("minecraft:water")
                : level.registry().facts(neighbor).has(ac.cult.blocksim.data.StateFacts.AIR);
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        var level = context.level(); int state = block.defaultState();
        var fluid = level.fluidAt(context.clickedPos());
        if (kind == Kind.KELP && part == Part.HEAD && (!water.contains(fluid.type()) || fluid.amount() != 8)) return -1;
        return isPlant(level, state, level.stateAt(context.clickedPos().relative(growth(level, state))))
            ? body(level, state).defaultState() : part == Part.HEAD ? level.initialPlantAge(context.clickedPos(), state) : state;
    }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        Direction direction = growth(level, state); var supportPos = pos.relative(direction.opposite());
        int support = level.stateAt(supportPos);
        return (kind != Kind.KELP || !cannotSupport.contains(level.registry().block(support).key()))
            && (isPlant(level, state, support) || level.isFaceSturdy(support, supportPos, direction));
    }

    private int convert(SimLevel level, int state, int target) {
        return kind == Kind.CAVE_VINES ? level.registry().with(target, "berries", level.registry().value(state, "berries")) : target;
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        Direction growth = growth(level, state);
        if (part == Part.BODY) {
            return direction == growth && !isPlant(level, state, neighborState)
                ? convert(level, state, level.initialPlantAge(pos, head(level, state).defaultState())) : state;
        }
        if (direction == growth.opposite() && canSurvive(level, state, pos)
            && isPlant(level, state, level.stateAt(pos.relative(growth)))) return convert(level, state, body(level, state).defaultState());
        if (direction == growth && isPlant(level, state, neighborState)) return convert(level, state, body(level, state).defaultState());
        // Unsupported plants and fluid updates only schedule server ticks.
        return state;
    }

    @Override
    public boolean canBeReplaced(SimLevel level, int state, PlacementContext context) {
        return super.canBeReplaced(level, state, context)
            && (part == Part.HEAD || !context.stack().is(head(level, state).bindings().get("asItem")));
    }

    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) {
        // Cave-vine harvesting writes and loot are guarded by ServerLevel.
        return kind == Kind.CAVE_VINES && bool(context.level(), state, "berries") ? SimInteraction.SUCCESS : SimInteraction.PASS;
    }

    @Override
    public boolean canPlaceLiquid(SimLevel level, int state, BlockPos pos, String fluidType, boolean creativePlayer) {
        return kind != Kind.KELP && super.canPlaceLiquid(level, state, pos, fluidType, creativePlayer);
    }

    @Override
    public boolean placeLiquid(SimLevel level, int state, BlockPos pos, SimFluidState fluid) {
        return kind != Kind.KELP && super.placeLiquid(level, state, pos, fluid);
    }
}
