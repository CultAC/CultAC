package ac.cult.blocksim.behavior;

import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;
import ac.cult.blocksim.data.ItemComponents;
import java.util.function.Consumer;

/** Client consumption; potion/omen/stew listeners operate on the action's player state. */
public final class ConsumableBehavior {
    private final Consumer<UseContext> effectListeners;
    public ConsumableBehavior(Consumer<UseContext> effectListeners) { this.effectListeners = java.util.Objects.requireNonNull(effectListeners); }

    public SimInteraction start(UseContext context) {
        if (!canConsume(context)) return SimInteraction.FAIL;
        int ticks = consumeTicks(context);
        if (ticks > 0) { context.player().startUsingItem(context.hand(), ticks); return SimInteraction.CONSUME; }
        onConsume(context);
        return SimInteraction.CONSUME.transformedTo(context.stack());
    }
    private boolean canConsume(UseContext context) {
        var food = ItemComponents.food(context.stack().components());
        return food == null || context.player().canEat(food.canAlwaysEat());
    }
    private int consumeTicks(UseContext context) {
        return ItemComponents.consumable(context.stack().components()).ticks();
    }
    public void onConsume(UseContext context) {
        foodListener(context);
        effectListeners.accept(context);
        // on_consume_effects are guarded by !level.isClientSide. Particle/sound RNG
        // affects only the already declared growing-plant age value set.
        context.stack().consume(1, context.player());
    }
    private void foodListener(UseContext context) {
        var food = ItemComponents.food(context.stack().components());
        if (food != null) context.player().addFood(food.nutrition(), food.saturation());
    }
}
