package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockRaycast;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;

/** BoatItem's client placement checks; spawning and consumption belong to the server. */
public final class BoatItemBehavior implements ItemBehavior {
    private final ItemBehavior defaults;
    private final EntityTypes types;
    public BoatItemBehavior(ItemBehavior defaults, EntityTypes types) { this.defaults = defaults; this.types = types; }
    @Override public SimInteraction useOn(UseContext context) { return defaults.useOn(context); }
    @Override public SimInteraction use(UseContext context) {
        var pick = BlockRaycast.playerPick(context.level(), context.player(), BlockRaycast.Fluid.ANY);
        if (pick.miss() || context.level().hasPickableEntityAtEye(context.player())) return SimInteraction.PASS;
        var type = types.byKey(context.usedItem().bindings().get("BoatItem.entityType"));
        if (!context.level().canSpawn(type) || !context.level().noBoatCollision(type.boxAt(pick.hit().location()), pick.hit().location()))
            return SimInteraction.FAIL;
        return SimInteraction.SUCCESS;
    }
}
