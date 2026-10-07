package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.Offsets;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Set;

public final class BambooStalkBehavior extends BlockBehavior {
    private final Set<String> supports;
    public BambooStalkBehavior(Set<String> supports) { this.supports = Set.copyOf(supports); }

    @Override
    public VoxelShape collisionShape(SimLevel level, int state, BlockPos pos) {
        var offset = Offsets.offset(level.registry().block(state), pos);
        return Shapes.create(6.5 / 16, 0, 6.5 / 16, 9.5 / 16, 1, 9.5 / 16).move(offset.x(), offset.y(), offset.z());
    }

    @Override
    public VoxelShape supportShape(SimLevel level, int state, BlockPos pos) { return collisionShape(level, state, pos); }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) { return supports.contains(level.registry().block(level.stateAt(pos.relative(Direction.DOWN))).key()); }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        SimLevel level = context.level(); BlockPos pos = context.clickedPos();
        if (!level.fluidAt(pos).isEmpty()) return -1;
        int below = level.stateAt(pos.relative(Direction.DOWN)); String key = level.registry().block(below).key();
        if (!supports.contains(key)) return -1;
        if (key.equals("minecraft:bamboo_sapling")) return level.registry().with(block.defaultState(), "age", "0");
        if (key.equals("minecraft:bamboo")) return level.registry().with(block.defaultState(), "age", number(level, below, "age") > 0 ? "1" : "0");
        int above = level.stateAt(pos.relative(Direction.UP));
        return level.registry().block(above).key().equals("minecraft:bamboo") ? level.registry().with(block.defaultState(), "age", level.registry().value(above, "age"))
            : level.registry().block("minecraft:bamboo_sapling").defaultState();
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        // Unsupported bamboo only schedules a server tick.
        return direction == Direction.UP && level.registry().block(neighborState).key().equals("minecraft:bamboo") && number(level, neighborState, "age") > number(level, state, "age")
            ? level.registry().with(state, "age", number(level, state, "age") == 0 ? "1" : "0") : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }
}
