package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Set;

public final class FarmlandBehavior extends BlockBehavior {
    private final Set<String> maintainsFarmland;
    public FarmlandBehavior(Set<String> maintainsFarmland) { this.maintainsFarmland = Set.copyOf(maintainsFarmland); }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        int above = level.stateAt(pos.relative(Direction.UP));
        return !level.registry().facts(above).has(StateFacts.SOLID) || shouldMaintainFarmland(level, pos);
    }

    private boolean shouldMaintainFarmland(SimLevel level, BlockPos pos) {
        return maintainsFarmland.contains(level.registry().block(level.stateAt(pos.relative(Direction.UP))).key());
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        return canSurvive(context.level(), block.defaultState(), context.clickedPos()) ? super.placementState(block, context)
            : context.level().registry().block(block.bindings().get("FarmlandBlock.baseBlock")).defaultState();
    }

}
