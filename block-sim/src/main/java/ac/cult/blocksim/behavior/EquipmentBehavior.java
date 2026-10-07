package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.*;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.enchantment.EnchantmentEffects;
import ac.cult.blocksim.interaction.*;

/** Equippable's single player swap path, including stack splitting and inventory insertion. */
public final class EquipmentBehavior {
    private final DataTables data;
    private final EnchantmentEffects enchantments;
    public EquipmentBehavior(DataTables data, InteractionRegistries registries) { this.data = data; this.enchantments = new EnchantmentEffects(registries); }

    public SimInteraction swap(UseContext context, ItemComponents.Equippable rule) {
        if (rule.allowedEntities() != null && !rule.allowedEntities().contains(data, "entity_type", "minecraft:player")) return SimInteraction.PASS;
        int slot = switch (rule.slot()) {
            case "mainhand" -> context.player().inventory().selected(); case "offhand" -> 40;
            case "feet" -> 36; case "legs" -> 37; case "chest" -> 38; case "head" -> 39;
            case "body" -> 41; case "saddle" -> 42;
            default -> throw new IllegalArgumentException("Unknown equipment slot " + rule.slot());
        };
        var player = context.player(); var held = context.stack(); var equipped = player.inventory().get(slot);
        if (locked(equipped) && player.state().gameMode() != ac.cult.blocksim.engine.SimPlayer.GameMode.CREATIVE
            || held.sameItemSameComponents(equipped)) return SimInteraction.FAIL;
        if (held.count() <= 1) {
            var toHand = equipped.isEmpty() ? held : equipped.copyAndClear();
            player.inventory().set(slot, player.state().gameMode() == ac.cult.blocksim.engine.SimPlayer.GameMode.CREATIVE ? held.copy() : held.copyAndClear());
            return SimInteraction.SUCCESS.transformedTo(toHand);
        }
        var toInventory = equipped.copyAndClear(); var toEquipment = held.copyWithCount(1);
        held.consume(1, player); player.inventory().set(slot, toEquipment);
        player.inventory().add(toInventory); // A full inventory drops the old equipment; entity creation is outside this contract.
        return SimInteraction.SUCCESS.transformedTo(held);
    }

    private boolean locked(SimItemStack stack) {
        return enchantments.has(stack, EnchantmentEffects.PREVENT_ARMOR_CHANGE);
    }
}
