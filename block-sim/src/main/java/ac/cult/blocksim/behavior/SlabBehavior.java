package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.SimFluidState;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.interaction.PlacementContext;

public final class SlabBehavior extends BlockBehavior {
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        SimLevel level = context.level(); BlockPos pos = context.clickedPos();
        int replaced = level.stateAt(pos);
        if (level.registry().block(replaced) == block) return level.registry().with(level.registry().with(replaced, "type", "double"), "waterlogged", "false");
        int result = level.registry().with(level.registry().with(block.defaultState(), "type", "bottom"), "waterlogged",
            Boolean.toString(level.fluidAt(pos).is("minecraft:water")));
        Direction face = context.clickedFace();
        return face != Direction.DOWN && (face == Direction.UP || !(context.clickLocation().y() - pos.y() > 0.5))
            ? result : level.registry().with(result, "type", "top");
    }

    @Override
    public boolean canBeReplaced(SimLevel level, int state, PlacementContext context) {
        String type = level.registry().value(state, "type");
        if (type.equals("double") || !context.stack().is(level.registry().block(state).bindings().get("asItem"))) return false;
        if (context.replacingClicked()) {
            boolean above = context.clickLocation().y() - context.clickedPos().y() > 0.5;
            Direction face = context.clickedFace();
            return type.equals("bottom") ? face == Direction.UP || above && face.horizontal() : face == Direction.DOWN || !above && face.horizontal();
        }
        return true;
    }
    @Override
    public boolean canPlaceLiquid(SimLevel level, int state, BlockPos pos, String fluidType, boolean creativePlayer) {
        return !level.registry().value(state, "type").equals("double") && SimpleWaterlogged.canPlaceLiquid(fluidType);
    }

    @Override
    public boolean placeLiquid(SimLevel level, int state, BlockPos pos, SimFluidState fluid) {
        return !level.registry().value(state, "type").equals("double") && SimpleWaterlogged.placeLiquid(level, state, fluid);
    }
}
