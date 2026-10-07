package ac.cult.blocksim.behavior;

import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;

/** MCP 26.3 ShearsItem.useOn: client sets maximum plant age; durability changes only on ServerLevel. */
public final class ShearsItemBehavior implements ItemBehavior {
    private final ItemBehavior general;
    public ShearsItemBehavior(ItemBehavior general) { this.general = general; }

    @Override public SimInteraction useOn(UseContext context) {
        var level = context.level();
        int state = level.stateAt(context.clickedPos());
        if (BlockBehavior.isFamily(level, state, "GrowingPlantHeadBlock")) {
            // GrowingPlantHeadBlock.MAX_AGE and getMaxAgeState use 25 for every head family.
            String maximum = "25";
            if (!level.registry().value(state, "age").equals(maximum)) {
                level.setBlock(context.clickedPos(), level.registry().with(state, "age", maximum), 3);
                return SimInteraction.SUCCESS;
            }
        }
        return general.useOn(context);
    }
    @Override public SimInteraction use(UseContext context) { return general.use(context); }
}
