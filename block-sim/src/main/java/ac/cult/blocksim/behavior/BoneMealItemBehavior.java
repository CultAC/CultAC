package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;

/** 26.3 BoneMealItem.useOn/growCrop/growWaterPlant: growth and consumption require ServerLevel. */
public final class BoneMealItemBehavior implements ItemBehavior {
    private final ItemBehavior fallback;
    private final BonemealTargets targets;

    public BoneMealItemBehavior(DataTables data, ItemBehavior fallback) {
        this.fallback = fallback;
        targets = new BonemealTargets(data);
    }

    @Override
    public SimInteraction useOn(UseContext context) {
        var level = context.level();
        var pos = context.clickedPos();
        int state = level.stateAt(pos);
        if (targets.valid(level, state, pos)) return SimInteraction.SUCCESS;
        var waterPos = pos.relative(context.clickedFace());
        if (level.isFaceSturdy(state, pos, context.clickedFace())
                && level.registry().block(level.stateAt(waterPos)).key().equals("minecraft:water")
                && level.fluidAt(waterPos).amount() == 8) return SimInteraction.SUCCESS;
        return SimInteraction.PASS;
    }

    @Override
    public SimInteraction use(UseContext context) { return fallback.use(context); }
}
