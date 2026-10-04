package ac.cult.cultac.network.codec;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.BlockTransformer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

/** Host-777-only holder resolution. Original vanilla codecs preserve custom named action data. */
public final class NativeBlockTransformers {
    private NativeBlockTransformers() {}

    public static void inline(JsonObject patch, ItemStack stack, RegistryAccess registries) {
        if (!patch.has("minecraft:block_transformer")) return;
        var holder = stack.get(DataComponents.BLOCK_TRANSFORMER);
        if (holder == null) throw new IllegalStateException("Transformer patch has no native value");
        patch.add("minecraft:block_transformer", resolved(holder.value(), registries));
    }

    /** Restores an internal direct action value to a real host registry holder before native patch decoding. */
    public static void restore(JsonObject patch, RegistryAccess registries, ItemStack preferred) {
        var value = patch.get("minecraft:block_transformer");
        if (value == null || !value.isJsonArray()) return;
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        var expected = resolved(BlockTransformer.DIRECT_CODEC.parse(ops, value).getOrThrow(), registries);
        var original = preferred == null ? null : preferred.get(DataComponents.BLOCK_TRANSFORMER);
        Holder<BlockTransformer> holder = original != null
                        && original.unwrapKey().isPresent()
                        && resolved(original.value(), registries).equals(expected)
                ? original
                : registries
                        .lookupOrThrow(Registries.BLOCK_TRANSFORMER)
                        .listElements()
                        .filter(candidate ->
                                resolved(candidate.value(), registries).equals(expected))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "Vanilla action returned a transformer absent from the host registry"));
        patch.addProperty(
                "minecraft:block_transformer",
                holder.unwrapKey().orElseThrow().identifier().toString());
    }

    /** Vanilla actions change count/damage/equipment, never the transformer's registry value. */
    public static boolean same(ItemStack left, ItemStack right, RegistryAccess registries) {
        var a = left.get(DataComponents.BLOCK_TRANSFORMER);
        var b = right.get(DataComponents.BLOCK_TRANSFORMER);
        return a != null && b != null && resolved(a.value(), registries).equals(resolved(b.value(), registries));
    }

    private static JsonArray resolved(BlockTransformer transformer, RegistryAccess registries) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        var encoded = BlockTransformer.DIRECT_CODEC
                .encodeStart(ops, transformer)
                .getOrThrow()
                .getAsJsonArray();
        Set<BlockStateProvider> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int index = 0; index < transformer.transforms().size(); index++)
            encoded.get(index)
                    .getAsJsonObject()
                    .add(
                            "block_state_provider",
                            provider(
                                    transformer
                                            .transforms()
                                            .get(index)
                                            .blockStateProvider()
                                            .value(),
                                    ops,
                                    visiting));
        return encoded;
    }

    private static JsonElement provider(
            BlockStateProvider value, RegistryOps<JsonElement> ops, Set<BlockStateProvider> visiting) {
        if (!visiting.add(value)) throw new IllegalStateException("Cyclic vanilla state provider");
        try {
            var encoded =
                    BlockStateProvider.DIRECT_CODEC.encodeStart(ops, value).getOrThrow();
            var object = encoded.getAsJsonObject();
            if (!object.has("type")) return object; // Simple state's exact Name/Properties representation.
            String type = object.get("type").getAsString();
            if (!type.contains(":")) type = "minecraft:" + type;
            // These are all holder-bearing fields in the original five provider codecs.
            switch (type) {
                case "minecraft:copy_properties", "minecraft:randomized_int" -> field(object, "source", ops, visiting);
                case "minecraft:rotated" -> field(object, "state", ops, visiting);
                case "minecraft:rule_based" -> {
                    if (object.has("fallback")) field(object, "fallback", ops, visiting);
                    for (var rule : object.getAsJsonArray("rules"))
                        field(rule.getAsJsonObject(), "then", ops, visiting);
                }
                default -> {}
            }
            return object;
        } finally {
            visiting.remove(value);
        }
    }

    private static void field(
            JsonObject object, String name, RegistryOps<JsonElement> ops, Set<BlockStateProvider> visiting) {
        var holder = BlockStateProvider.CODEC.parse(ops, object.get(name)).getOrThrow();
        object.add(name, provider(holder.value(), ops, visiting));
    }
}
