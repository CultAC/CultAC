package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.*;

/** Shared shelf hit-slot geometry. Storage changes are server-only in both families. */
final class SlotContainerUse {
    private final DataTables data;
    SlotContainerUse(DataTables data) { this.data = data; }
    SimInteraction use(int state, UseContext context, boolean bookshelf, boolean withItem) {
        var entity = context.level().blockEntityAt(context.clickedPos());
        if (entity == null || !entity.type().equals(bookshelf ? "minecraft:chiseled_bookshelf" : "minecraft:shelf")) return SimInteraction.PASS;
        if (!bookshelf && context.hand() == Hand.OFF_HAND) return SimInteraction.PASS;
        if (bookshelf && withItem && !data.tags().get("item:minecraft:bookshelf_books").contains(context.stack().itemKey())) return SimInteraction.TRY_WITH_EMPTY_HAND;
        int slot = hitSlot(context, Direction.valueOf(context.level().registry().value(state, "facing").toUpperCase(java.util.Locale.ROOT)), bookshelf ? 2 : 1);
        if (slot < 0) return SimInteraction.PASS;
        if (!bookshelf) return context.player().hand(Hand.MAIN_HAND).isEmpty() ? SimInteraction.PASS : SimInteraction.CONSUME;
        boolean occupied = Boolean.parseBoolean(context.level().registry().value(state, "slot_" + slot + "_occupied"));
        return withItem ? occupied ? SimInteraction.TRY_WITH_EMPTY_HAND : SimInteraction.SUCCESS
            : occupied ? SimInteraction.SUCCESS : SimInteraction.CONSUME;
    }
    private static int hitSlot(UseContext context, Direction facing, int rows) {
        if (context.clickedFace() != facing) return -1;
        var adjacent = context.clickedPos().relative(facing); var hit = context.clickLocation();
        float x = switch (facing) {
            case NORTH -> (float)(1.0 - (hit.x() - adjacent.x())); case SOUTH -> (float)(hit.x() - adjacent.x());
            case WEST -> (float)(hit.z() - adjacent.z()); case EAST -> (float)(1.0 - (hit.z() - adjacent.z()));
            default -> throw new IllegalArgumentException("Vertical shelf facing");
        };
        return section(x, 3) + section(1.0F - (float)(hit.y() - adjacent.y()), rows) * 3;
    }
    private static int section(float coordinate, int count) { return Math.min(Math.max((int)Math.floor(coordinate * 16.0F / (16.0F / count)), 0), count - 1); }
}
