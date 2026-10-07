package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Set;

public final class BigDripleafBehavior extends BlockBehavior {
    private final Set<String> supportTag;
    public BigDripleafBehavior(Set<String> supportTag) { this.supportTag = Set.copyOf(supportTag); }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        SimLevel level = context.level();
        int below = level.stateAt(context.clickedPos().relative(Direction.DOWN));
        String key = level.registry().block(below).key();
        String direction = key.equals("minecraft:big_dripleaf") || key.equals("minecraft:big_dripleaf_stem")
            ? level.registry().value(below, "facing") : context.horizontalDirection().opposite().name().toLowerCase(java.util.Locale.ROOT);
        int state = level.registry().with(block.defaultState(), "waterlogged", Boolean.toString(level.fluidAt(context.clickedPos()).isSourceOfType("minecraft:water")));
        return level.registry().with(state, "facing", direction);
    }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        int below = level.stateAt(pos.relative(Direction.DOWN));
        String belowKey = level.registry().block(below).key();
        return level.registry().sameBlock(state, below) || belowKey.equals("minecraft:big_dripleaf_stem") || supportTag.contains(belowKey);
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        if (direction == Direction.DOWN && !canSurvive(level, state, pos)) return level.registry().block("minecraft:air").defaultState();
        // The native fluid tick has no client effect.
        if (direction == Direction.UP && level.registry().sameBlock(state, neighborState)) {
            int stem = level.registry().block("minecraft:big_dripleaf_stem").defaultState();
            stem = level.registry().with(stem, "facing", level.registry().value(state, "facing"));
            return level.registry().with(stem, "waterlogged", level.registry().value(state, "waterlogged"));
        }
        return super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }
}
