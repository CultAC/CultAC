package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.interaction.Hand;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;
import java.util.Set;

/** Bow/Crossbow client use and Player.getProjectile; shooting/loading mutations are server-only. */
public final class ProjectileWeaponBehavior implements ItemBehavior {
    private final ItemBehavior defaults;
    private final boolean crossbow;
    private final Set<String> arrows;
    public ProjectileWeaponBehavior(DataTables data, ItemBehavior defaults, boolean crossbow) {
        this.defaults = defaults; this.crossbow = crossbow;
        arrows = data.tags().get("item:minecraft:arrows");
    }
    @Override public SimInteraction useOn(UseContext context) { return defaults.useOn(context); }
    @Override public SimInteraction use(UseContext context) {
        var charged = context.stack().components().get("minecraft:charged_projectiles");
        if (crossbow && charged != null && !charged.getAsJsonArray().isEmpty()) return SimInteraction.CONSUME;
        if (!hasProjectile(context.player())) return SimInteraction.FAIL;
        context.player().startUsingItem(context.hand(), 72000);
        return SimInteraction.CONSUME;
    }
    private boolean hasProjectile(SimPlayer player) {
        if (player.state().infiniteMaterials()) return true;
        for (Hand hand : Hand.values()) {
            var stack = player.hand(hand);
            if (!stack.isEmpty() && (arrows.contains(stack.itemKey()) || crossbow && stack.is("minecraft:firework_rocket"))) return true;
        }
        for (var stack : player.inventory().slots()) if (!stack.isEmpty() && arrows.contains(stack.itemKey())) return true;
        return false;
    }
    @Override public int useDuration(SimItemStack stack, SimPlayer player) { return 72000; }
}
