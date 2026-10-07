package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.SimFluidState;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.interaction.PlacementContext;
import ac.cult.blocksim.interaction.UseContext;
import ac.cult.blocksim.interaction.SimInteraction;
import java.util.Locale;
import java.util.Set;

public final class CampfireBehavior extends BlockBehavior {
    private final Set<String> dousingItems;
    public CampfireBehavior(Set<String> dousingItems) { this.dousingItems = Set.copyOf(dousingItems); }
    private static boolean isSmokeSource(SimLevel level, int state) { return level.registry().block(state).key().equals("minecraft:hay_block"); }

    @Override public SimInteraction useItemOn(int state, UseContext context) {
        var level = context.level(); var entity = level.blockEntityAt(context.clickedPos());
        if (entity != null && entity.type().equals("minecraft:campfire")
            && level.isRecipeInput("minecraft:campfire_input", context.player().hand(context.hand()))) return SimInteraction.CONSUME;
        if (!dousingItems.contains(context.stack().itemKey()) || !bool(level, state, "lit")) return SimInteraction.TRY_WITH_EMPTY_HAND;
        level.setBlock(context.clickedPos(), level.registry().with(state, "lit", "false"), 3);
        // Food insertion and ItemStack.hurtAndBreak require ServerLevel.
        return SimInteraction.SUCCESS;
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        SimLevel level = context.level(); BlockPos pos = context.clickedPos(); boolean water = level.fluidAt(pos).is("minecraft:water");
        int state = level.registry().with(block.defaultState(), "waterlogged", Boolean.toString(water));
        state = level.registry().with(state, "signal_fire", Boolean.toString(isSmokeSource(level, level.stateAt(pos.relative(Direction.DOWN)))));
        state = level.registry().with(state, "lit", Boolean.toString(!water));
        return level.registry().with(state, "facing", context.horizontalDirection().name().toLowerCase(Locale.ROOT));
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        // Water scheduling has no client effect.
        return direction == Direction.DOWN ? level.registry().with(state, "signal_fire", Boolean.toString(isSmokeSource(level, neighborState)))
            : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }

    @Override
    public boolean placeLiquid(SimLevel level, int state, BlockPos pos, SimFluidState fluid) {
        if (bool(level, state, "waterlogged") || !fluid.is("minecraft:water")) return false;
        // douse only emits particles/game events; scheduled fluid ticks are client no-ops.
        int placed = level.registry().with(level.registry().with(state, "waterlogged", "true"), "lit", "false");
        level.setBlock(pos, placed, 3);
        return true;
    }
}
