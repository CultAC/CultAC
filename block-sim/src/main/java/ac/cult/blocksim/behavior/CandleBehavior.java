package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.SimFluidState;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.interaction.PlacementContext;
import ac.cult.blocksim.interaction.UseContext;
import ac.cult.blocksim.interaction.SimInteraction;

public final class CandleBehavior extends BlockBehavior {
    private final SupportRules support;
    public CandleBehavior(SupportRules support) { this.support = support; }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        return support.canSupportCenter(level, pos.relative(Direction.DOWN), Direction.UP);
    }

    @Override
    public boolean canBeReplaced(SimLevel level, int state, PlacementContext context) {
        return !context.secondaryUseActive() && context.stack().is(level.registry().block(state).bindings().get("asItem"))
            && number(level, state, "candles") < 4 || super.canBeReplaced(level, state, context);
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        SimLevel level = context.level();
        int previous = level.stateAt(context.clickedPos());
        if (level.registry().block(previous).key().equals(block.key())) {
            return level.registry().with(previous, "candles", Integer.toString(number(level, previous, "candles") % 4 + 1));
        }
        return level.registry().with(super.placementState(block, context), "waterlogged", Boolean.toString(level.fluidAt(context.clickedPos()).is("minecraft:water")));
    }

    @Override
    public SimInteraction useItemOn(int state, UseContext context) {
        if (context.stack().isEmpty() && context.player().state().mayBuild() && bool(context.level(), state, "lit")) {
            context.level().setBlock(context.clickedPos(), context.level().registry().with(state, "lit", "false"), 11);
            return SimInteraction.SUCCESS;
        }
        return super.useItemOn(state, context);
    }

    @Override
    public boolean placeLiquid(SimLevel level, int state, BlockPos pos, SimFluidState fluid) {
        if (bool(level, state, "waterlogged") || !fluid.is("minecraft:water")) return false;
        int placed = level.registry().with(state, "waterlogged", "true");
        if (bool(level, state, "lit")) {
            // AbstractCandleBlock.extinguish -> setLit uses flags 11, even on the client.
            level.setBlock(pos, level.registry().with(placed, "lit", "false"), 11);
        } else level.setBlock(pos, placed, 3);
        return true;
    }
}
