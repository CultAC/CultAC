package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;
import java.util.Set;

/** MinecartItem placement, including the experimental client's initial rail snap. */
public final class MinecartItemBehavior implements ItemBehavior {
    private final ItemBehavior defaults;
    private final EntityTypes types;
    private final Set<String> rails;
    public MinecartItemBehavior(DataTables data, ItemBehavior defaults, EntityTypes types) {
        this.defaults = defaults; this.types = types; rails = data.tags().getOrDefault("block:minecraft:rails", Set.of());
    }
    @Override public SimInteraction use(UseContext context) { return defaults.use(context); }
    @Override public SimInteraction useOn(UseContext context) {
        var level = context.level(); var pos = context.clickedPos(); int state = level.stateAt(pos);
        if (!isTaggedRail(level, state)) return SimInteraction.FAIL;
        var type = types.byKey(context.usedItem().bindings().get("MinecartItem.type"));
        if (!level.canSpawn(type)) return SimInteraction.FAIL;
        String shape = isRailBlock(level, state) ? level.registry().value(state, "shape") : "north_south";
        var position = new Vec3(pos.x() + .5, pos.y() + .0625 + (shape.startsWith("ascending_") ? .5 : 0), pos.z() + .5);
        if (level.hasFeature("minecraft:minecart_improvements")) {
            var below = new BlockPos(pos.x(), (int) Math.floor(position.y() - .1 - 1.0E-5F), pos.z());
            var railPos = isTaggedRail(level, level.stateAt(below)) ? below
                : new BlockPos(pos.x(), (int) Math.floor(position.y()), pos.z());
            int railState = level.stateAt(railPos);
            if (isTaggedRail(level, railState) && isRailBlock(level, railState)) {
                String snapped = level.registry().value(railState, "shape");
                double x = position.x(), z = position.z();
                if (snapped.equals("south_east") || snapped.equals("north_east")) x += .25;
                if (snapped.equals("south_west") || snapped.equals("north_west")) x -= .25;
                if (snapped.equals("south_east") || snapped.equals("south_west")) z += .25;
                if (snapped.equals("north_east") || snapped.equals("north_west")) z -= .25;
                position = new Vec3(x, railPos.y() + (snapped.startsWith("ascending_") ? .6 : .1), z);
            }
            if (level.hasMinecartIn(type.boxAt(position))) return SimInteraction.FAIL;
        }
        context.stack().shrink(1);
        return SimInteraction.SUCCESS;
    }
    private boolean isTaggedRail(SimLevel level, int state) { return rails.contains(level.registry().block(state).key()); }
    private boolean isRailBlock(SimLevel level, int state) {
        return level.registry().block(state).bindings().get("classHierarchy").contains("net.minecraft.world.level.block.BaseRailBlock");
    }
}
