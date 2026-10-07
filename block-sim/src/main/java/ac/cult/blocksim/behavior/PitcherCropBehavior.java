package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Set;

public final class PitcherCropBehavior extends DoublePlantBehavior {
    public PitcherCropBehavior(Set<String> cropSupport, Set<String> water) { super(cropSupport, water); }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) { return block.defaultState(); }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return number(level, state, "age") >= 3 ? super.updateShape(level, state, pos, direction, neighborPos, neighborState)
            : canSurvive(level, state, pos) ? state : level.registry().block("minecraft:air").defaultState();
    }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        // The crop light clause uses CropBlock.hasSufficientLight and follows section 4.8.
        return super.canSurvive(level, state, pos);
    }

    @Override
    protected boolean mayPlaceOn(SimLevel level, int state, BlockPos pos) { return super.mayPlaceOn(level, state, pos); }

    @Override
    public boolean canBeReplaced(SimLevel level, int state, PlacementContext context) { return false; }

    @Override
    public void setPlacedBy(SimLevel level, int state, BlockPos pos, SimPlayer player, SimItemStack stack) { }
}
