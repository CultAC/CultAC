package ac.cult.cultac.network.codec;

import ac.cult.cultac.utils.minecraft.MinecraftRegistries;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.component.MapPostProcessing;

/** Persistent component codecs plus the two consumed, network-only component values. */
public final class ObservedComponents {
    private ObservedComponents() {}

    public static DataComponentPatch decode(MinecraftRegistries registries, CompoundTag values) {
        var builder = DataComponentPatch.builder();
        String map = "minecraft:map_post_processing", lock = "minecraft:creative_slot_lock";
        if (values.contains(map)) {
            builder.set(
                    DataComponents.MAP_POST_PROCESSING,
                    MapPostProcessing.valueOf(
                            values.getString(map).orElseThrow().toUpperCase(java.util.Locale.ROOT)));
            values.remove(map);
        }
        if (values.contains(lock)) {
            builder.set(DataComponents.CREATIVE_SLOT_LOCK, net.minecraft.util.Unit.INSTANCE);
            values.remove(lock);
        }
        if (values.contains("!" + map)) {
            builder.remove(DataComponents.MAP_POST_PROCESSING);
            values.remove("!" + map);
        }
        if (values.contains("!" + lock)) {
            builder.remove(DataComponents.CREATIVE_SLOT_LOCK);
            values.remove("!" + lock);
        }
        // Empty network predicate lists are valid and match no blocks; the persistent codec requires a nonempty list.
        for (var entry : java.util.Map.of(
                        "minecraft:can_break",
                        DataComponents.CAN_BREAK,
                        "minecraft:can_place_on",
                        DataComponents.CAN_PLACE_ON)
                .entrySet()) {
            if (values.get(entry.getKey()) instanceof ListTag list && list.isEmpty()) {
                builder.set(entry.getValue(), new net.minecraft.world.item.AdventureModePredicate(java.util.List.of()));
                values.remove(entry.getKey());
            }
        }
        // Nested stacks also carry network-only values. Build their model templates recursively.
        if (values.get("minecraft:bundle_contents") instanceof ListTag list) {
            var contents = new java.util.ArrayList<net.minecraft.world.item.ItemStackTemplate>();
            for (var child : list) {
                var stack = (CompoundTag) child;
                var item = ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil.registryOptional(
                                net.minecraft.core.registries.BuiltInRegistries.ITEM,
                                stack.getString("id").orElseThrow())
                        .orElseThrow()
                        .builtInRegistryHolder();
                var components = stack.get("components") instanceof CompoundTag patch
                        ? decode(registries, patch)
                        : DataComponentPatch.EMPTY;
                contents.add(
                        new net.minecraft.world.item.ItemStackTemplate(item, stack.getIntOr("count", 1), components));
            }
            builder.set(
                    DataComponents.BUNDLE_CONTENTS, new net.minecraft.world.item.component.BundleContents(contents));
            values.remove("minecraft:bundle_contents");
        }
        var patch = DataComponentPatch.CODEC
                .parse(RegistryOps.create(NbtOps.INSTANCE, registries.access()), values)
                .getOrThrow();
        var split = patch.split();
        builder.set(split.added());
        split.removed().forEach(builder::remove);
        return builder.build();
    }
}
