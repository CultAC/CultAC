package ac.cult.cultac.network.codec;

import ac.cult.blocksim.data.ComponentLayout;
import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.ItemTemplate;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.protocol.ProtocolResolutionException;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.util.List;

/** Nested templates keep their raw count, patch, child encodings and optional slot layout. */
final class ModelNestedItemWriter {
    private static final List<String> SIDES = List.of("back", "left", "right", "front");

    private ModelNestedItemWriter() {}

    static boolean supports(String key) {
        return switch (key) {
            case "minecraft:bundle_contents",
                    "minecraft:use_remainder",
                    "minecraft:charged_projectiles",
                    "minecraft:container",
                    "minecraft:pot_decorations" -> true;
            default -> false;
        };
    }

    static void write(
            ModelItemValues items,
            ProtocolVersion version,
            WireValueDecoder.Registries registries,
            ByteBuf output,
            String key,
            Components components) {
        if (key.equals("minecraft:bundle_contents")) {
            var contents = components.bundle().items();
            Wire.writeVarInt(output, contents.size());
            contents.forEach(item -> items.writeTemplate(output, item));
            return;
        }
        var value = ModelComponentWriter.value(components, key);
        switch (key) {
            case "minecraft:use_remainder" -> items.writeTemplate(output, template(components, key, "0", value));
            case "minecraft:charged_projectiles" -> {
                var contents = ((NbtValue.Sequence) value).values();
                Wire.writeVarInt(output, contents.size());
                for (int index = 0; index < contents.size(); index++)
                    items.writeTemplate(
                            output, template(components, key, Integer.toString(index), contents.get(index)));
            }
            case "minecraft:container" -> {
                int size = ComponentLayout.containerSlots(components);
                if (size < 0 || size > 256)
                    throw new ProtocolResolutionException("Invalid container slot count: " + size);
                var slots = new ItemTemplate[size];
                for (var entry : ((NbtValue.Sequence) value).values()) {
                    var fields = ((NbtValue.Compound) entry).values();
                    int slot = ((NbtValue.Numeric) fields.get("slot")).value().intValue();
                    if (slot < 0 || slot >= size)
                        throw new ProtocolResolutionException("Invalid container slot: " + slot);
                    slots[slot] = template(components, key, Integer.toString(slot), fields.get("item"));
                }
                Wire.writeVarInt(output, size);
                for (var item : slots) optional(items, version, output, item);
            }
            case "minecraft:pot_decorations" -> {
                if (!version.atLeast(ProtocolVersion.V26_3)) Wire.writeVarInt(output, SIDES.size());
                for (int index = 0; index < SIDES.size(); index++) {
                    String side = SIDES.get(index);
                    NbtValue item = value instanceof NbtValue.Compound fields
                            ? fields.values().get(side)
                            : index < ((NbtValue.Sequence) value).values().size()
                                    ? ((NbtValue.Sequence) value).values().get(index)
                                    : null;
                    var template = item == null ? null : template(components, key, side, item);
                    if (version.atLeast(ProtocolVersion.V26_3)) optional(items, version, output, template);
                    else {
                        if (template != null
                                && (template.count() != 1
                                        || !template.patch().added().keys().isEmpty()
                                        || !template.patch().removed().isEmpty()))
                            throw new ProtocolResolutionException(
                                    "Legacy pot decoration cannot carry an item patch or count");
                        String name = template == null ? "minecraft:brick" : template.itemKey();
                        int id = registries.id("minecraft:item", name);
                        if (id < 0) throw new ProtocolResolutionException("Unknown pot decoration item: " + name);
                        Wire.writeVarInt(output, id);
                    }
                }
            }
            default -> throw new ProtocolResolutionException("Unknown nested item component: " + key);
        }
    }

    private static ItemTemplate template(Components components, String key, String index, NbtValue value) {
        var item = ItemTemplate.fromNbt(value, ComponentLayout.child(components, key, index));
        var encoding = components.wireEncoding(key);
        return encoding == null ? item : item.withWireEncodings(encoding.item(index));
    }

    private static void optional(ModelItemValues items, ProtocolVersion version, ByteBuf output, ItemTemplate item) {
        if (version.atLeast(ProtocolVersion.V26_1)) output.writeBoolean(item != null);
        else if (item == null) Wire.writeVarInt(output, 0);
        if (item != null) items.writeTemplate(output, item);
    }
}
