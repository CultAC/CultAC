package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.*;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.PlacementContext;
import ac.cult.blocksim.interaction.UseContext;
import ac.cult.blocksim.interaction.SimInteraction;
import java.util.Locale;

public final class DecoratedPotBehavior extends BlockBehavior {
    private final java.util.Set<String> breaksPots, preventsShattering;
    public DecoratedPotBehavior(DataTables data) {
        breaksPots = data.tags().get("item:minecraft:breaks_decorated_pots");
        preventsShattering = data.tags().get("enchantment:minecraft:prevents_decorated_pot_shattering");
    }
    @Override
    public void playerWillDestroy(SimLevel level, int state, BlockPos pos, SimPlayer player) {
        var stack = player.hand(ac.cult.blocksim.interaction.Hand.MAIN_HAND);
        var enchantments = stack.components().get("minecraft:enchantments");
        boolean protectedPot = enchantments != null && enchantments.getAsJsonObject().keySet().stream().anyMatch(preventsShattering::contains);
        if (breaksPots.contains(stack.itemKey()) && !protectedPot) level.setBlock(pos, level.registry().with(state, "cracked", "true"), 260);
    }
    @Override
    public SimInteraction useItemOn(int state, UseContext context) { return useWithoutItem(state, context); }
    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) {
        BlockEntityData entity = context.level().blockEntityAt(context.clickedPos());
        return entity != null && entity.type().equals("minecraft:decorated_pot") ? SimInteraction.SUCCESS : SimInteraction.PASS;
    }
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        int state = context.level().registry().with(block.defaultState(), "facing", context.horizontalDirection().name().toLowerCase(Locale.ROOT));
        state = context.level().registry().with(state, "waterlogged", Boolean.toString(context.level().fluidAt(context.clickedPos()).is("minecraft:water")));
        return context.level().registry().with(state, "cracked", "false");
    }
}
