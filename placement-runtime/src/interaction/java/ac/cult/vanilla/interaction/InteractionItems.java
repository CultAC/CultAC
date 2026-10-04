package ac.cult.vanilla.interaction;

import ac.cult.placement.api.InteractionEngine;
import ac.cult.placement.api.InteractionEngine.Stack;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

/**
 * Transfers an item type, count and its patch of {@link InteractionEngine#TRANSFERRED_COMPONENTS}
 * through vanilla's component patch codec; every other component is the type's default.
 */
final class InteractionItems {
    private InteractionItems() {}

    static ItemStack decode(Stack input, RegistryAccess registries) {
        if (input.count() <= 0 || input.item().equals("minecraft:air")) return ItemStack.EMPTY;
        var item = BuiltInRegistries.ITEM.get(Identifier.parse(input.item())).orElseThrow();
        if (input.components() == null) return new ItemStack(item, input.count());
        var patch = ModelBootstrap.decodeComponents(JsonParser.parseString(input.components()), registries);
        return new ItemStack(item, input.count(), transferred(patch));
    }

    static Stack encode(ItemStack stack, RegistryAccess registries) {
        if (stack.isEmpty()) return Stack.EMPTY;
        var patch = transferred(stack.getComponentsPatch());
        return new Stack(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount(),
                patch.isEmpty()
                        ? null
                        : ModelBootstrap.encodeComponents(patch, registries).toString());
    }

    static DataComponentPatch nativeDecode(JsonElement input, RegistryAccess registries) {
        return DataComponentPatch.CODEC
                .parse(RegistryOps.create(JsonOps.INSTANCE, registries), input)
                .getOrThrow();
    }

    static JsonElement nativeEncode(DataComponentPatch patch, RegistryAccess registries) {
        return DataComponentPatch.CODEC
                .encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), patch)
                .getOrThrow();
    }

    private static DataComponentPatch transferred(DataComponentPatch patch) {
        return patch.forget(type -> !InteractionEngine.TRANSFERRED_COMPONENTS.contains(
                BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString()));
    }
}
