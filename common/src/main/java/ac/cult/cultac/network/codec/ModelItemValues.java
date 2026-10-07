package ac.cult.cultac.network.codec;

import ac.cult.blocksim.data.ComponentPatch;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.data.ModelIdMappings;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import io.netty.buffer.ByteBuf;

/** Owned item values for every supported wire schema, including the native model version. */
public final class ModelItemValues {
    private final WireValueDecoder decoder;
    private final ProtocolVersion version;
    private final WireValueDecoder.Registries registries;
    private final ItemRegistry items;
    private final ModelIdMappings mappings;

    public ModelItemValues(
            WireValueDecoder decoder,
            ProtocolVersion version,
            WireValueDecoder.Registries registries,
            ItemRegistry items) {
        this.decoder = java.util.Objects.requireNonNull(decoder);
        this.version = java.util.Objects.requireNonNull(version);
        this.registries = java.util.Objects.requireNonNull(registries);
        this.items = java.util.Objects.requireNonNull(items);
        this.mappings = decoder.mappings(version, ProtocolVersion.V26_3);
    }

    public SimItemStack item(ByteBuf input, boolean creative) {
        return item(decoder.item(version, input, creative, registries));
    }

    public SimItemStack itemCost(ByteBuf input, boolean optional) {
        return item(decoder.itemCost(version, input, optional, registries));
    }

    /** Click claims expose item/count only; hashes cannot reconstruct component values. */
    public SimItemStack hashedItem(ByteBuf input) {
        return item(decoder.hashedItem(version, input));
    }

    /** Writes owned stacks without decoding untouched component payloads again. */
    public void write(ByteBuf output, SimItemStack stack) {
        ac.cult.cultac.protocol.wire.Wire.writeVarInt(output, stack.count());
        if (stack.isEmpty()) return;
        int itemId = registries.id("minecraft:item", stack.itemKey());
        if (itemId < 0)
            throw new ac.cult.cultac.protocol.ProtocolResolutionException("Unknown wire item " + stack.itemKey());
        ac.cult.cultac.protocol.wire.Wire.writeVarInt(output, itemId);
        var raw = stack.wirePatch();
        if (raw != null && raw.matches(version.protocol(), registries::id)) output.writeBytes(raw.buffer());
        else writePatch(output, stack.patch());
    }

    /** Templates use item/count/patch and keep the raw count and unsanitized patch. */
    public void writeTemplate(ByteBuf output, ac.cult.blocksim.data.ItemTemplate item) {
        int id = registries.id("minecraft:item", item.itemKey());
        if (id < 0)
            throw new ac.cult.cultac.protocol.ProtocolResolutionException("Unknown wire item " + item.itemKey());
        if (version.atLeast(ProtocolVersion.V26_1)) {
            ac.cult.cultac.protocol.wire.Wire.writeVarInt(output, id);
            ac.cult.cultac.protocol.wire.Wire.writeVarInt(output, item.count());
        } else {
            if (item.count() <= 0)
                throw new ac.cult.cultac.protocol.ProtocolResolutionException("Nonpositive legacy nested item count");
            ac.cult.cultac.protocol.wire.Wire.writeVarInt(output, item.count());
            ac.cult.cultac.protocol.wire.Wire.writeVarInt(output, id);
        }
        writePatch(output, item.patch());
    }

    private void writePatch(ByteBuf output, ComponentPatch patch) {
        ac.cult.cultac.protocol.wire.Wire.writeVarInt(
                output, patch.added().keys().size());
        ac.cult.cultac.protocol.wire.Wire.writeVarInt(output, patch.removed().size());
        for (String key : patch.added().keys()) {
            componentId(output, key);
            writeComponent(output, key, patch.added());
        }
        for (String key : patch.removed()) componentId(output, key);
    }

    public void writeComponent(ByteBuf output, String key, ac.cult.blocksim.data.Components components) {
        var encoding = components.wireEncoding(key);
        if (encoding != null && encoding.matches(version.protocol(), registries::id))
            output.writeBytes(encoding.buffer());
        else if (ModelNestedItemWriter.supports(key))
            ModelNestedItemWriter.write(this, version, registries, output, key, components);
        else ModelComponentWriter.write(output, key, components, decoder, version, registries);
    }

    public boolean canWriteComponent(String key) {
        return ModelNestedItemWriter.supports(key) || decoder.canWriteComponent(key);
    }

    private void componentId(ByteBuf output, String key) {
        int id = registries.id("minecraft:data_component_type", key);
        if (id < 0) throw new ac.cult.cultac.protocol.ProtocolResolutionException("Unknown wire component " + key);
        ac.cult.cultac.protocol.wire.Wire.writeVarInt(output, id);
    }

    public static ComponentPatch patch(WireValueDecoder.ItemValue value) {
        var patch = ComponentPatch.fromNetwork(value.components(), value.componentLayouts());
        if (value.componentEncodings().isEmpty()) return patch;
        var encodings = new java.util.HashMap<String, ac.cult.blocksim.data.ComponentWireEncoding>();
        value.componentEncodings().forEach((key, encoding) -> encodings.put(key, encoding(encoding)));
        return new ComponentPatch(patch.added().withWireEncodings(encodings), patch.removed());
    }

    private static ac.cult.blocksim.data.ComponentWireEncoding encoding(WireValueDecoder.ComponentEncoding value) {
        var children =
                new java.util.HashMap<String, java.util.Map<String, ac.cult.blocksim.data.ComponentWireEncoding>>();
        value.items().forEach((slot, components) -> {
            var encodings = new java.util.HashMap<String, ac.cult.blocksim.data.ComponentWireEncoding>();
            components.forEach((key, child) -> encodings.put(key, encoding(child)));
            children.put(slot, java.util.Map.copyOf(encodings));
        });
        return new ac.cult.blocksim.data.ComponentWireEncoding(
                value.protocol(), value.bytes(), value.references(), children);
    }

    public SimItemStack item(WireValueDecoder.ItemValue value) {
        if (value.count() <= 0) return items.empty();
        String key = registries.preserveIdentifiers()
                ? registries.name("minecraft:item", value.id())
                : ModelRegistryData.load(ProtocolVersion.V26_3)
                        .registry("minecraft:item")
                        .name(mappings.item(value.id()));
        var stack = items.stack(key, value.count(), patch(value));
        if (value.wirePatch() != null) stack.wirePatch(encoding(value.wirePatch()));
        return stack;
    }
}
