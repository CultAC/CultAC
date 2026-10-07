package ac.cult.blocksim.interaction;

import ac.cult.blocksim.behavior.ItemBehaviorRegistry;
import ac.cult.blocksim.data.ItemTemplates;
import ac.cult.blocksim.data.ItemComponents;
import ac.cult.blocksim.engine.SimCooldowns;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimPlayer;

/** ItemStack's client wrappers, before item dispatch and after the item result. */
public final class ItemStackActions implements StackActions {
    private final ItemBehaviorRegistry behaviors;
    private final AdventureRuleMatcher adventure;
    private final SimCooldowns cooldowns;
    private final ItemTemplates templates;
    public ItemStackActions(ItemBehaviorRegistry behaviors, AdventureRuleMatcher adventure, SimCooldowns cooldowns, ItemTemplates templates) {
        this.behaviors = java.util.Objects.requireNonNull(behaviors); this.adventure = java.util.Objects.requireNonNull(adventure);
        this.cooldowns = java.util.Objects.requireNonNull(cooldowns); this.templates = java.util.Objects.requireNonNull(templates);
    }

    @Override
    public SimInteraction useOn(SimItemStack original, UseContext context) {
        if (context.player() != null && !context.player().state().mayBuild()
            && !adventure.test(original, "minecraft:can_place_on", context.level(), context.clickedPos())) return SimInteraction.PASS;
        var before = original.copy();
        var item = behaviors.itemForStack(original);
        var result = behaviors.behavior(item).useOn(context.forItem(item));
        if (context.player() != null && result.consumesAction() && result.itemInteraction()) {
            var transformed = result.transformedStack() == null ? original : result.transformedStack();
            return result.transformedTo(afterUse(transformed, context.player(), before));
        }
        return result;
    }

    @Override
    public SimInteraction use(SimItemStack original, UseContext context) {
        var before = original.copy();
        var item = behaviors.itemForStack(original);
        var behavior = behaviors.behavior(item);
        boolean instant = behavior.useDuration(original, context.player()) <= 0;
        var result = behavior.use(context.forItem(item));
        if (instant && result.consumesAction()) {
            var transformed = result.transformedStack() == null ? original : result.transformedStack();
            return result.transformedTo(afterUse(transformed, context.player(), before));
        }
        return result;
    }

    private SimItemStack afterUse(SimItemStack used, SimPlayer player, SimItemStack before) {
        var remainder = before.components().get("minecraft:use_remainder");
        var cooldown = ItemComponents.useCooldown(before.components());
        SimItemStack result = used;
        if (remainder != null) result = remainder(used, before, player);
        if (cooldown != null) cooldowns.add(before, cooldown.ticks());
        return result;
    }

    private SimItemStack remainder(SimItemStack used, SimItemStack before, SimPlayer player) {
        if (player.state().infiniteMaterials() || used.count() >= before.count()) return used;
        var components = before.components();
        var remainder = templates.create(ItemComponents.useRemainder(components));
        if (used.isEmpty()) return remainder;
        extraCreatedRemainder(remainder);
        return used;
    }

    private void extraCreatedRemainder(SimItemStack remainder) {
        // Neither Player, AbstractClientPlayer nor LocalPlayer overrides this empty callback.
    }
}
