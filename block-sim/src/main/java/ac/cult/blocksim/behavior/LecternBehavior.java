package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockEntityData;
import ac.cult.blocksim.interaction.*;
import java.util.Set;

public final class LecternBehavior extends BlockBehavior {
    private final Set<String> books;
    public LecternBehavior(Set<String> books) { this.books = Set.copyOf(books); }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        int state = context.level().registry().with(block.defaultState(), "facing", context.horizontalDirection().opposite().name().toLowerCase(java.util.Locale.ROOT));
        // The item BlockEntityData Book inspection is guarded by !isClientSide.
        return context.level().registry().with(state, "has_book", "false");
    }
    @Override
    public SimInteraction useItemOn(int state, UseContext context) {
        if (bool(context.level(), state, "has_book")) return SimInteraction.TRY_WITH_EMPTY_HAND;
        // tryPlaceBook succeeds here; placeBook and stack consumption are server-only.
        if (books.contains(context.stack().itemKey())) return SimInteraction.SUCCESS;
        return context.stack().isEmpty() && context.hand() == Hand.MAIN_HAND ? SimInteraction.PASS : SimInteraction.TRY_WITH_EMPTY_HAND;
    }
    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) {
        return bool(context.level(), state, "has_book") ? SimInteraction.SUCCESS : SimInteraction.CONSUME;
    }
    @Override
    public int ownSignal(SimLevel level, int state, BlockPos pos) {
        return bool(level, state, "powered") ? 15 : 0;
    }

    @Override
    public int directSignal(SimLevel level, int state, BlockPos pos, Direction direction) {
        return direction == Direction.UP && bool(level, state, "powered") ? 15 : 0;
    }
}
