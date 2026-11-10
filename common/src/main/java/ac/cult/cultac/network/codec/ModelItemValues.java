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
import java.util.function.Supplier;

/** Owned item values for every supported wire schema, including the native model version. */
public final class ModelItemValues {
    private final WireValueDecoder decoder;
    private final ProtocolVersion version;
    private final WireValueDecoder.Registries registries;
    private final ItemRegistry items;
    private final ModelIdMappings mappings;
    private final ItemStackCache cache;
    private final Supplier<?> generation;
    private final CachedStacks cached;
    private final LaterStacks later;
    private int count;

    public ModelItemValues(
            WireValueDecoder decoder,
            ProtocolVersion version,
            WireValueDecoder.Registries registries,
            ItemRegistry items) {
        this(decoder, version, registries, items, null, null);
    }

    /** {@code generation} changes whenever an answer of {@code registries} can. */
    ModelItemValues(
            WireValueDecoder decoder,
            ProtocolVersion version,
            WireValueDecoder.Registries registries,
            ItemRegistry items,
            ItemStackCache cache,
            Supplier<?> generation) {
        this.decoder = Objects.requireNonNull(decoder);
        this.version = Objects.requireNonNull(version);
        this.registries = Objects.requireNonNull(registries);
        this.items = Objects.requireNonNull(items);
        this.mappings = decoder.mappings(version, ProtocolVersion.V26_3);
        this.cache = cache;
        this.generation = cache == null ? null : Objects.requireNonNull(generation);
        this.cached = cache == null ? null : new CachedStacks();
        this.later = cache == null ? null : new LaterStacks();
    }

    public SimItemStack item(ByteBuf input, boolean creative) {
        return item(decoder.item(version, input, creative, registries, ModelNbtFactory.INSTANCE));
    }

    /** As {@code item(input, false)}, for a stack the server sent; each call returns a stack of its own. */
    SimItemStack sentItem(ByteBuf input) {
        if (cache == null || cache.observing()) return item(input, false);
        cache.bind(decoder, version, items, generation.get());
        return decoder.item(version, input, registries, ModelNbtFactory.INSTANCE, cached);
    }

    /** Whether {@link #sentLater} defers any conversion. */
    boolean convertsLater() {
        return cache != null && !cache.observing();
    }

    /**
     * As {@link #sentItem}, throwing what it throws and leaving the held stacks as it does; the stack is converted when
     * first read, and every read returns that stack. Only while {@link #convertsLater}.
     */
    Supplier<SimItemStack> sentLater(ByteBuf input) {
        cache.bind(decoder, version, items, generation.get());
        return decoder.item(version, input, registries, ModelNbtFactory.INSTANCE, later);
    }

    /** The slot of the held stack {@code input} continues with, consumed, its count in {@code count}; -1 if none. */
    private int held(ByteBuf input) {
        int start = input.readerIndex(), count = 0, index = start, next;
        do {
            if (index == input.writerIndex() || index - start == 5) return -1;
            next = input.getUnsignedByte(index);
            count |= (next & 0x7f) << ((index++ - start) * 7);
        } while ((next & 0x80) != 0);
        if (count <= 0) return -1;
        int slot = cache.find(input, index);
        if (slot < 0) return -1;
        input.readerIndex(index + cache.length(slot));
        this.count = count;
        return slot;
    }

    /** Where the key of a stack decoded from {@code start} begins: after its leading count. */
    private static int keyStart(ByteBuf input, int start) {
        int index = start;
        while ((input.getUnsignedByte(index++) & 0x80) != 0) {}
        return index;
    }

    /**
     * Held stacks are keyed by the bytes after the leading count, which reaches a decoded stack only as its count, so
     * one held stack serves every positive count. The structure checks read no byte past a stack they accept, so input
     * that begins with a held key holds that stack.
     */
    private final class CachedStacks implements WireValueDecoder.ItemReuse<NbtValue, SimItemStack> {
        @Override
        public SimItemStack reuse(ByteBuf input) {
            int slot = held(input);
            return slot < 0 ? null : cache.stack(slot).copyWithCount(count);
        }

        @Override
        public SimItemStack decoded(WireValueDecoder.DecodedItem<NbtValue> value, ByteBuf input, int start, int end) {
            var stack = item(value);
            int index = keyStart(input, start);
            // Bundle contents memoize their weight and are never shared between received stacks.
            if (input.readerIndex() == end
                    && end - index <= ItemStackCache.MAX_BYTES
                    && !stack.isEmpty()
                    && stack.patch().added().bundle() == null) {
                int size = ItemStackCache.retained(end - index, value.components(), value.componentLayouts());
                cache.put(input, index, end, stack.copy(), size);
            }
            return stack;
        }
    }

    /**
     * As {@link CachedStacks}, holding the same keys at the same moments with the same sizes. A stack that {@link
     * Pending#defers} is held and handed out unconverted; its item key resolves now, so every failure stays here.
     */
    private final class LaterStacks implements WireValueDecoder.ItemReuse<NbtValue, Supplier<SimItemStack>> {
        @Override
        public Supplier<SimItemStack> reuse(ByteBuf input) {
            int slot = held(input);
            if (slot < 0) return null;
            Object held = cache.held(slot);
            return new Counted(
                    held instanceof Pending pending ? pending.copy() : new Pending((SimItemStack) held), count);
        }

        @Override
        public Supplier<SimItemStack> decoded(
                WireValueDecoder.DecodedItem<NbtValue> value, ByteBuf input, int start, int end) {
            if (!Pending.defers(value)) return new Pending(cached.decoded(value, input, start, end));
            String key = key(value.id());
            items.item(key);
            int index = keyStart(input, start);
            // A deferred stack has no bundle contents and a positive count.
            if (input.readerIndex() == end
                    && end - index <= ItemStackCache.MAX_BYTES
                    && !key.equals("minecraft:air")) {
                int size = ItemStackCache.retained(end - index, value.components(), value.componentLayouts());
                cache.put(input, index, end, new Pending(items, key, value), size);
            }
            return new Pending(items, key, value);
        }
    }

    /**
     * A sent stack, converted on its first read by the one thread reading it. Converting reads only the decoded value,
     * its resolved item key and an item registry whose answers never change.
     */
    static final class Pending implements Supplier<SimItemStack> {
        private static final int DEPTH = 64;

        private final ItemRegistry items;
        private final String key;
        private WireValueDecoder.DecodedItem<NbtValue> value;
        private SimItemStack stack;

        Pending(ItemRegistry items, String key, WireValueDecoder.DecodedItem<NbtValue> value) {
            this.items = items;
            this.key = key;
            this.value = value;
        }

        Pending(SimItemStack stack) {
            this(null, null, null);
            this.stack = stack;
        }

        /** A copy for another reader, unconverted while this one is. */
        Pending copy() {
            return stack != null ? new Pending(stack) : new Pending(items, key, value);
        }

        @Override
        public SimItemStack get() {
            var converted = stack;
            if (converted == null) {
                converted = stack = ModelItemValues.stack(items, key, value.count(), patch(value), value.wirePatch());
                value = null;
            }
            return converted;
        }

        /**
         * Whether converting {@code value} after its item key resolved can neither throw nor read more than itself: a
         * positive count, compound components with no bundle contents, block predicates or tooltip display, layouts
         * without signed zeros, and trees whose depth leaves the recursive conversion far from any stack limit.
         */
        static boolean defers(WireValueDecoder.DecodedItem<NbtValue> value) {
            if (value.count() <= 0 || !(value.components() instanceof NbtValue.Compound components)) return false;
            for (String key : components.values().keySet()) {
                switch (key) {
                    case "minecraft:bundle_contents",
                            "minecraft:can_break",
                            "minecraft:can_place_on",
                            "minecraft:tooltip_display" -> {
                        return false;
                    }
                    default -> {}
                }
            }
            NbtValue layouts = value.componentLayouts();
            if (layouts != null) {
                if (!(layouts instanceof NbtValue.Compound compound)) return false;
                for (NbtValue layout : compound.values().values())
                    if (layout instanceof NbtValue.Compound fields && fields.values().containsKey("negative_zero"))
                        return false;
                if (!total(layouts, 0)) return false;
            }
            return total(components, 0);
        }

        /** Whether {@code value} holds no null leaf and nests at most {@link #DEPTH} below {@code depth}. */
        private static boolean total(NbtValue value, int depth) {
            if (depth > DEPTH) return false;
            if (value instanceof NbtValue.Numeric numeric) return numeric.value() != null;
            if (value instanceof NbtValue.Text text) return text.value() != null;
            if (value instanceof NbtValue.PrimitiveArray) return true;
            if (value instanceof NbtValue.Compound compound) {
                for (NbtValue child : compound.values().values()) if (!total(child, depth + 1)) return false;
                return true;
            }
            for (NbtValue child : ((NbtValue.Sequence) value).values()) if (!total(child, depth + 1)) return false;
            return true;
        }
    }

    /** A held stack at another positive count, copied on its first read. */
    private static final class Counted implements Supplier<SimItemStack> {
        private final Pending held;
        private final int count;
        private SimItemStack stack;

        Counted(Pending held, int count) {
            this.held = held;
            this.count = count;
        }

        @Override
        public SimItemStack get() {
            var copy = stack;
            if (copy == null) copy = stack = held.get().copyWithCount(count);
            return copy;
        }
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
        if (value.items().isEmpty())
            return new ComponentWireEncoding(value.protocol(), value.bytes(), value.references());
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
        return stack(items, key(id), count, patch, wirePatch);
    }

    private String key(int id) {
        return registries.preserveIdentifiers()
                ? registries.name("minecraft:item", id)
                : ModelRegistryData.load(ProtocolVersion.V26_3)
                        .registry("minecraft:item")
                        .name(mappings.item(id));
    }

    private static SimItemStack stack(
            ItemRegistry items,
            String key,
            int count,
            ComponentPatch patch,
            WireValueDecoder.ComponentEncoding wirePatch) {
        var stack = items.stack(key, count, patch);
        if (wirePatch != null) stack.wirePatch(encoding(wirePatch));
        return stack;
    }
}
