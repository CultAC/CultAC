package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimLevel;
import java.util.Set;

/** Shared vanilla connection predicates and their generated constant tags. */
public final class ConnectionRules {
    final Set<String> walls, fences, woodenFences, wallPostOverride;
    private final Set<String> shulkers;
    public ConnectionRules(DataTables data) {
        walls = data.tags().get("block:minecraft:walls"); fences = data.tags().get("block:minecraft:fences");
        woodenFences = data.tags().get("block:minecraft:wooden_fences"); wallPostOverride = data.tags().get("block:minecraft:wall_post_override");
        shulkers = data.tags().get("block:minecraft:shulker_boxes");
    }

    boolean exception(SimLevel level, int state) {
        String key = level.registry().block(state).key();
        return BlockBehavior.isFamily(level, state, "LeavesBlock") || key.equals("minecraft:barrier") || key.equals("minecraft:carved_pumpkin")
            || key.equals("minecraft:jack_o_lantern") || key.equals("minecraft:melon") || key.equals("minecraft:pumpkin") || shulkers.contains(key);
    }
}
