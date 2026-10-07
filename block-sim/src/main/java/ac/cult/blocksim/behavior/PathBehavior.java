package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;

public final class PathBehavior extends BlockBehavior {
    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        int above = level.stateAt(pos.relative(Direction.UP));
        return !level.registry().facts(above).has(StateFacts.SOLID) || isFamily(level, above, "FenceGateBlock");
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        // pushEntitiesUp returns its supplied dirt state without another block write.
        return canSurvive(context.level(), block.defaultState(), context.clickedPos()) ? super.placementState(block, context)
            : context.level().registry().block("minecraft:dirt").defaultState();
    }

}
