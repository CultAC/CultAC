package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import java.util.Set;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.interaction.PlacementContext;

public class DoublePlantBehavior extends VegetationBehavior {
    public enum Kind { PLAIN, SEAGRASS, DRIPLEAF }
    private final Set<String> water;
    private final Kind kind;
    public DoublePlantBehavior(Set<String> supportTag, Set<String> water) {
        super(supportTag); this.water = Set.copyOf(water); kind = Kind.PLAIN;
    }
    public DoublePlantBehavior(DataTables data, Kind kind) {
        super(data, switch (kind) { case PLAIN -> VegetationBehavior.Kind.NORMAL; case SEAGRASS -> VegetationBehavior.Kind.SEAGRASS; case DRIPLEAF -> VegetationBehavior.Kind.SMALL_DRIPLEAF; });
        water = data.tags().get("fluid:minecraft:water"); this.kind = kind;
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        var level = context.level(); var above = context.clickedPos().relative(Direction.UP); int aboveState = level.stateAt(above);
        if (context.clickedPos().y() >= level.maxY() || !level.behavior(aboveState).canBeReplaced(level, aboveState, context)) return -1;
        if (kind == Kind.SEAGRASS && !fullWater(level, above)) return -1;
        int state = block.defaultState();
        return kind == Kind.DRIPLEAF ? copyWaterlogged(level, context.clickedPos(),
            level.registry().with(state, "facing", context.horizontalDirection().opposite().name().toLowerCase(java.util.Locale.ROOT))) : state;
    }

    protected int copyWaterlogged(SimLevel level, BlockPos pos, int state) {
        return level.registry().hasProperty(state, "waterlogged") ? level.registry().with(state, "waterlogged", Boolean.toString(water.contains(level.fluidAt(pos).type()))) : state;
    }

    @Override
    public void setPlacedBy(SimLevel level, int state, BlockPos pos, SimPlayer player, SimItemStack stack) {
        // SmallDripleafBlock writes its upper half only on the server.
        if (kind == Kind.DRIPLEAF) return;
        var above = pos.relative(Direction.UP); int upper = level.registry().with(level.registry().block(state).defaultState(), "half", "upper");
        level.setBlock(above, copyWaterlogged(level, above, upper), 3);
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        String half = level.registry().value(state, "half");
        if (direction.horizontal() || half.equals("lower") != (direction == Direction.UP)
            || level.registry().sameBlock(state, neighborState) && !level.registry().value(neighborState, "half").equals(half)) {
            return half.equals("lower") && direction == Direction.DOWN && !canSurvive(level, state, pos)
                ? level.registry().block("minecraft:air").defaultState() : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
        }
        return level.registry().block("minecraft:air").defaultState();
    }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        if (!level.registry().value(state, "half").equals("upper"))
            return super.canSurvive(level, state, pos) && (kind != Kind.SEAGRASS || fullWater(level, pos));
        int below = level.stateAt(pos.relative(Direction.DOWN));
        return level.registry().sameBlock(state, below) && level.registry().value(below, "half").equals("lower");
    }
    private boolean fullWater(SimLevel level, BlockPos pos) {
        var fluid = level.fluidAt(pos); return water.contains(fluid.type()) && fluid.amount() == 8;
    }
}
