package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.BlockRaycast;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;
import java.util.Set;

/** Client acknowledgements whose server branch changes entities or saved map data. */
public final class ContextualItemBehavior implements ItemBehavior {
    public enum Kind { LEAD, FILLED_MAP, ENDER_EYE, FIREWORK }
    private final ItemBehavior defaults;
    private final Kind kind;
    private final Set<String> banners;
    private final ac.cult.blocksim.data.BlockDefinition portalFrame;

    public ContextualItemBehavior(DataTables data, ItemBehavior defaults, Kind kind) {
        this.defaults = defaults;
        this.kind = kind;
        banners = data.tags().getOrDefault("block:minecraft:banners", Set.of());
        portalFrame = data.registry().block("minecraft:end_portal_frame");
    }

    @Override public SimInteraction useOn(UseContext context) {
        var level = context.level();
        return switch (kind) {
            case LEAD -> SimInteraction.PASS;
            case FILLED_MAP -> banners.contains(level.registry().block(level.stateAt(context.clickedPos())).key())
                ? SimInteraction.SUCCESS : defaults.useOn(context);
            case ENDER_EYE -> {
                int state = level.stateAt(context.clickedPos());
                yield level.registry().block(state) == portalFrame && level.registry().value(state, "eye").equals("false")
                    ? SimInteraction.SUCCESS : SimInteraction.PASS;
            }
            case FIREWORK -> context.player() != null && context.player().movement().fallFlying()
                ? SimInteraction.PASS : SimInteraction.SUCCESS;
        };
    }

    @Override public SimInteraction use(UseContext context) {
        if (kind == Kind.FIREWORK) return context.player().movement().fallFlying() ? SimInteraction.SUCCESS : SimInteraction.PASS;
        if (kind != Kind.ENDER_EYE) return defaults.use(context);
        var pick = BlockRaycast.playerPick(context.level(), context.player(), BlockRaycast.Fluid.NONE);
        if (!pick.miss() && context.level().registry().block(context.level().stateAt(pick.hit().pos())) == portalFrame)
            return SimInteraction.PASS;
        context.player().startUsingItem(context.hand(), 0);
        return SimInteraction.SUCCESS_SERVER;
    }

    @Override public int useDuration(SimItemStack stack, SimPlayer player) {
        return kind == Kind.ENDER_EYE ? 0 : defaults.useDuration(stack, player);
    }
}
