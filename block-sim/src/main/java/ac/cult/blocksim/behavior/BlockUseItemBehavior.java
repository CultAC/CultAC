package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.*;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.*;

/** Small item-on-block mutations share dispatch, while retaining their vanilla write flags. */
final class BlockUseItemBehavior implements ItemBehavior {
    enum Kind { FLINT_AND_STEEL, FIRE_CHARGE, HONEYCOMB }
    private final Kind kind;
    private final ItemBehavior general;
    private final PortalFrame portals;
    private final DataTables data;
    BlockUseItemBehavior(DataTables data, ItemBehavior general, Kind kind) {
        this.kind = kind; this.general = general; this.data = data;
        portals = new PortalFrame(data.tags().get("block:minecraft:nether_portal_frame"), data.tags().get("block:minecraft:fire"));
    }
    @Override public SimInteraction use(UseContext context) { return general.use(context); }
    @Override public SimInteraction useOn(UseContext context) {
        return kind == Kind.HONEYCOMB ? wax(context) : ignite(context);
    }
    private SimInteraction wax(UseContext context) {
        var level = context.level(); int old = level.stateAt(context.clickedPos());
        String waxed = level.registry().block(old).bindings().get("waxed");
        if (waxed == null) return SimInteraction.PASS;
        context.stack().shrink(1);
        level.setBlock(context.clickedPos(), level.registry().withPropertiesOf(level.registry().block(waxed).defaultState(), old), 11);
        return SimInteraction.SUCCESS;
    }
    private SimInteraction ignite(UseContext context) {
        var level = context.level(); var pos = context.clickedPos(); int state = level.stateAt(pos);
        if (canLight(level, state)) state = level.registry().with(state, "lit", "true");
        else {
            pos = pos.relative(context.clickedFace());
            if (!level.registry().facts(level.stateAt(pos)).has(StateFacts.AIR)) return SimInteraction.FAIL;
            var fire = (BaseFireBehavior)level.behavior(level.registry().block("minecraft:fire").defaultState());
            state = fire.fireState(level, pos);
            if (!level.behavior(state).canSurvive(level, state, pos) && !portals.canIgnite(level, pos, context.horizontalDirection())) return SimInteraction.FAIL;
        }
        level.setBlock(pos, state, kind == Kind.FIRE_CHARGE ? 3 : 11);
        // Native hurtAndBreak is server-only; FireChargeItem explicitly shrinks on the client.
        if (kind == Kind.FIRE_CHARGE) context.stack().shrink(1);
        return SimInteraction.SUCCESS;
    }
    private boolean canLight(SimLevel level, int state) {
        String key = level.registry().block(state).key();
        if (!level.registry().hasProperty(state, "lit") || Boolean.parseBoolean(level.registry().value(state, "lit"))) return false;
        if (data.tags().get("block:minecraft:candle_cakes").contains(key)) return true;
        return (data.tags().get("block:minecraft:candles").contains(key) || data.tags().get("block:minecraft:campfires").contains(key))
            && level.registry().hasProperty(state, "waterlogged") && !Boolean.parseBoolean(level.registry().value(state, "waterlogged"));
    }
}
