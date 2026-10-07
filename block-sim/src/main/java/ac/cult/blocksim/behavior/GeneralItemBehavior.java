package ac.cult.blocksim.behavior;

import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;
import ac.cult.blocksim.data.ItemComponents;
import com.google.gson.JsonElement;
import java.util.function.BiFunction;

/** Item's component-driven defaults; transformer and equipment mechanics have their own handlers. */
public final class GeneralItemBehavior implements ItemBehavior {
    private final ConsumableBehavior consumables;
    private final BiFunction<UseContext, JsonElement, SimInteraction> transformers;
    private final BiFunction<UseContext, ItemComponents.Equippable, SimInteraction> equipment;
    public GeneralItemBehavior(ConsumableBehavior consumables, BiFunction<UseContext, JsonElement, SimInteraction> transformers,
                               BiFunction<UseContext, ItemComponents.Equippable, SimInteraction> equipment) {
        this.consumables = java.util.Objects.requireNonNull(consumables); this.transformers = java.util.Objects.requireNonNull(transformers);
        this.equipment = java.util.Objects.requireNonNull(equipment);
    }
    @Override
    public SimInteraction useOn(UseContext context) {
        var transformer = context.stack().components().get("minecraft:block_transformer");
        return transformer == null ? SimInteraction.PASS : transformers.apply(context, transformer);
    }
    @Override
    public SimInteraction use(UseContext context) {
        var stack = context.player().hand(context.hand());
        var actual = context.forStack(stack);
        if (stack.components().has("minecraft:consumable")) return consumables.start(actual);
        var equippable = ItemComponents.equippable(stack.components());
        if (equippable != null && equippable.swappable()) return equipment.apply(actual, equippable);
        if (stack.components().has("minecraft:blocks_attacks") || stack.components().has("minecraft:kinetic_weapon")) {
            context.player().startUsingItem(context.hand(), useDuration(stack, context.player()));
            return SimInteraction.CONSUME;
        }
        return SimInteraction.PASS;
    }
}
