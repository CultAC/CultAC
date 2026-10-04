package ac.cult.vanilla.interaction;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.BlockTransformer;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.RegistryOps;

/** Internal resolved action values; vanilla's network component still uses named registry holders. */
final class TransformerComponents {
    private static final String NAME = "minecraft:block_transformer";

    private TransformerComponents() {}

    static DataComponentPatch decode(JsonElement input, RegistryAccess registries) {
        var object = input.getAsJsonObject();
        var encoded = object.get(NAME);
        if (encoded == null || !encoded.isJsonArray()) return InteractionItems.nativeDecode(input, registries);
        var ordinary = object.deepCopy();
        ordinary.remove(NAME);
        var patch = InteractionItems.nativeDecode(ordinary, registries);
        var transformer = BlockTransformer.DIRECT_CODEC
                .parse(RegistryOps.create(JsonOps.INSTANCE, registries), encoded)
                .getOrThrow();
        var builder = DataComponentPatch.builder();
        var split = patch.split();
        builder.set(split.added());
        split.removed().forEach(builder::remove);
        builder.set(DataComponents.BLOCK_TRANSFORMER, Holder.direct(transformer));
        return builder.build();
    }

    static JsonElement encode(DataComponentPatch patch, RegistryAccess registries) {
        var supplied = patch.split().added().get(DataComponents.BLOCK_TRANSFORMER);
        // A removal is distinct from an absent addition and stays in the native patch codec.
        if (supplied == null) return InteractionItems.nativeEncode(patch, registries);
        var ordinary = patch.forget(type -> type == DataComponents.BLOCK_TRANSFORMER);
        var encoded = InteractionItems.nativeEncode(ordinary, registries).getAsJsonObject();
        encoded.add(
                NAME,
                BlockTransformer.DIRECT_CODEC
                        .encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), supplied.value())
                        .getOrThrow());
        return encoded;
    }
}
