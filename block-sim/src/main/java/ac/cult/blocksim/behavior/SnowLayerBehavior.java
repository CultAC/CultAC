package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.shapes.SupportType;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Set;

public final class SnowLayerBehavior extends BlockBehavior {
    private final Set<String> cannotSupport, supportOverride;
    public SnowLayerBehavior(Set<String> cannotSupport, Set<String> supportOverride) { this.cannotSupport = Set.copyOf(cannotSupport); this.supportOverride = Set.copyOf(supportOverride); }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        var below = pos.relative(Direction.DOWN); int support = level.stateAt(below); String key = level.registry().block(support).key();
        if (cannotSupport.contains(key)) return false;
        return supportOverride.contains(key) || SupportType.FULL.supports(level.behavior(support).collisionShape(level, support, below), Direction.UP)
            || level.registry().sameBlock(state, support) && number(level, support, "layers") == 8;
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return !canSurvive(level, state, pos) ? level.registry().block("minecraft:air").defaultState()
            : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }

    @Override
    public boolean canBeReplaced(SimLevel level, int state, PlacementContext context) {
        int layers = number(level, state, "layers");
        if (!context.stack().is(level.registry().block(state).bindings().get("asItem")) || layers >= 8) return layers == 1;
        return !context.replacingClicked() || context.clickedFace() == Direction.UP;
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        int old = context.level().stateAt(context.clickedPos());
        return context.level().registry().block(old) == block ? context.level().registry().with(old, "layers", Integer.toString(Math.min(8, number(context.level(), old, "layers") + 1)))
            : super.placementState(block, context);
    }
}
