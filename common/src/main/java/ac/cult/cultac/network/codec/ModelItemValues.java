package ac.cult.cultac.network.codec;

import ac.cult.blocksim.data.ComponentPatch;
import ac.cult.blocksim.data.ComponentWireEncoding;
import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.data.ItemTemplate;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.protocol.ProtocolResolutionException;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.data.ModelIdMappings;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
        this.decoder = Objects.requireNonNull(decoder);
        this.version = Objects.requireNonNull(version);
        this.registries = Objects.requireNonNull(registries);
        this.items = Objects.requireNonNull(items);
        this.mappings = decoder.mappings(version, ProtocolVersion.V26_3);
    }

    public SimItemStack item(ByteBuf input, boolean creative) {
        return item(decoder.item(version, input, creative, registries, ModelNbtFactory.INSTANCE));
    }

    public SimItemStack itemCost(ByteBuf input, boolean optional) {
        return item(decoder.itemCost(version, input, optional, registries, ModelNbtFactory.INSTANCE));
    }

    /** Click claims expose item/count only; hashes cannot reconstruct component values. */
    public SimItemStack hashedItem(ByteBuf input) {
        return item(decoder.hashedItem(version, input));
    }

    /** Writes owned stacks without decoding untouched component payloads again. */
    public void write(ByteBuf output, SimItemStack stack) {
        Wire.writeVarInt(output, stack.count());
        if (stack.isEmpty()) return;
        int itemId = registries.id("minecraft:item", stack.itemKey());
        if (itemId < 0) throw new ProtocolResolutionException("Unknown wire item " + stack.itemKey());
        Wire.writeVarInt(output, itemId);
        var raw = stack.wirePatch();
        if (raw != null && raw.matches(version.protocol(), registries::id)) output.writeBytes(raw.buffer());
        else writePatch(output, stack.patch());
    }

    /** Templates use item/count/patch and keep the raw count and unsanitized patch. */
    public void writeTemplate(ByteBuf output, ItemTemplate item) {
        int id = registries.id("minecraft:item", item.itemKey());
        if (id < 0) throw new ProtocolResolutionException("Unknown wire item " + item.itemKey());
        if (version.atLeast(ProtocolVersion.V26_1)) {
            Wire.writeVarInt(output, id);
            Wire.writeVarInt(output, item.count());
        } else {
            if (item.count() <= 0) throw new ProtocolResolutionException("Nonpositive legacy nested item count");
            Wire.writeVarInt(output, item.count());
            Wire.writeVarInt(output, id);
        }
        writePatch(output, item.patch());
    }

    private void writePatch(ByteBuf output, ComponentPatch patch) {
        Wire.writeVarInt(output, patch.added().keys().size());
        Wire.writeVarInt(output, patch.removed().size());
        for (String key : patch.added().keys()) {
            componentId(output, key);
            writeComponent(output, key, patch.added());
        }
        for (String key : patch.removed()) componentId(output, key);
    }

    public void writeComponent(ByteBuf output, String key, Components components) {
        var encoding = components.wireEncoding(key);
        if (encoding != null && encoding.matches(version.protocol(), registries::id))
            output.writeBytes(encoding.buffer());
        else if (ModelNestedItemWriter.supports(key))
            ModelNestedItemWriter.write(this, version, registries, output, key, components);
        else ModelComponentWriter.write(output, key, components, decoder, version, registries);
    }

    private void componentId(ByteBuf output, String key) {
        int id = registries.id("minecraft:data_component_type", key);
        if (id < 0) throw new ProtocolResolutionException("Unknown wire component " + key);
        Wire.writeVarInt(output, id);
    }

    public static ComponentPatch patch(WireValueDecoder.ItemValue value) {
        var patch = ComponentPatch.fromNetwork(value.components(), value.componentLayouts());
        if (value.componentEncodings().isEmpty()) return patch;
        var encodings = new HashMap<String, ComponentWireEncoding>();
        value.componentEncodings().forEach((key, encoding) -> encodings.put(key, encoding(encoding)));
        return new ComponentPatch(patch.added().withWireEncodings(encodings), patch.removed());
    }

    /** Host item capture uses the same direct component bridge as packet decoding. */
    public static ComponentPatch readPatch(
            WireValueDecoder decoder,
            ProtocolVersion version,
            ByteBuf input,
            boolean creative,
            WireValueDecoder.Registries registries) {
        return patch(decoder.item(version, input, creative, registries, ModelNbtFactory.INSTANCE));
    }

    private static ComponentPatch patch(WireValueDecoder.DecodedItem<NbtValue> value) {
        var patch = value.components() == null
                ? ComponentPatch.EMPTY
                : ComponentPatch.fromNbt(
                        (NbtValue.Compound) value.components(),
                        value.componentLayouts() == null
                                ? new NbtValue.Compound(Map.of())
                                : (NbtValue.Compound) value.componentLayouts());
        if (value.componentEncodings().isEmpty()) return patch;
        var encodings = new HashMap<String, ComponentWireEncoding>();
        value.componentEncodings().forEach((key, encoding) -> encodings.put(key, encoding(encoding)));
        return new ComponentPatch(patch.added().withWireEncodings(encodings), patch.removed());
    }

    private static ComponentWireEncoding encoding(WireValueDecoder.ComponentEncoding value) {
        var children = new HashMap<String, Map<String, ComponentWireEncoding>>();
        value.items().forEach((slot, components) -> {
            var encodings = new HashMap<String, ComponentWireEncoding>();
            components.forEach((key, child) -> encodings.put(key, encoding(child)));
            children.put(slot, Map.copyOf(encodings));
        });
        return new ComponentWireEncoding(value.protocol(), value.bytes(), value.references(), children);
    }

    public SimItemStack item(WireValueDecoder.DecodedItem<NbtValue> value) {
        return stack(value.id(), value.count(), patch(value), value.wirePatch());
    }

    public SimItemStack item(WireValueDecoder.ItemValue value) {
        return stack(value.id(), value.count(), patch(value), value.wirePatch());
    }

    private SimItemStack stack(int id, int count, ComponentPatch patch, WireValueDecoder.ComponentEncoding wirePatch) {
        if (count <= 0) return items.empty();
        String key = registries.preserveIdentifiers()
                ? registries.name("minecraft:item", id)
                : ModelRegistryData.load(ProtocolVersion.V26_3)
                        .registry("minecraft:item")
                        .name(mappings.item(id));
        var stack = items.stack(key, count, patch);
        if (wirePatch != null) stack.wirePatch(encoding(wirePatch));
        return stack;
    }
}
