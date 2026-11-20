package ac.cult.cultac.codec;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.PacketResourceLimitException;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.NbtSkipper;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.shaded.vialib.api.minecraft.data.StructuredDataKey;
import ac.cult.shaded.vialib.api.minecraft.data.predicate.DataComponentPredicate;
import ac.cult.shaded.vialib.api.minecraft.item.data.StatePropertyMatcher;
import ac.cult.shaded.vialib.api.type.Types;
import ac.cult.shaded.vialib.api.type.types.version.VersionedTypesHolder;
import ac.cult.shaded.vialib.util.Key;
import io.netty.buffer.ByteBuf;

/**
 * Replays the structured item wire format before the decoder materializes it so a small frame
 * cannot expand into an unbounded object graph or call stack.
 *
 * <p>The item readers recurse through container, charge, bundle, remainder, pot and
 * adventure-predicate components without any depth or count limit: deep nesting overflows the
 * stack and wide nesting exhausts the heap. The scan walks exactly the framing of the readers it
 * fronts (including the 26.x item template order and the creative-mode length-prefixed components),
 * recurses only into item-bearing components, and skips every other component payload through
 * the same {@link ac.cult.shaded.vialib.api.type.Type} instance the decoder will use, so an
 * accepted structure consumes exactly the bytes the decoder expects. Parse failures keep their
 * ordinary decode-path behavior; only structures beyond the budgets below raise
 * {@link PacketResourceLimitException}.
 *
 * <p>Callers pass {@code input.duplicate()} to validate without consuming, or the live input to
 * consume the structure where the raw bytes are retained by the caller.
 */
public final class ItemStructureGuard {
    // A vanilla client stays far below every limit; together they bound one decode's work.
    private static final int MAX_DEPTH = 32;
    private static final int MAX_ITEMS = 4096;
    private static final int MAX_COMPONENTS = 8192;
    private static final int MAX_COMPONENTS_PER_ITEM = 512;
    private static final int MAX_CONTAINER_SLOTS = 256; // ContainerContentsType maxLength
    private static final int MAX_ITEM_ARRAY = 1024; // itemTemplateArray maxLength
    private static final int MAX_BLOCK_PREDICATES = 1024;
    private static final int MAX_EXACT_PREDICATES = 1024;
    /** Matches the depth cap of the tag limiters the guarded readers build. */
    private static final int MAX_NBT_DEPTH = 512;
    /** Armour overrides of a direct trim material; vanilla's materials override a few of its equipment assets. */
    static final int MAX_TRIM_OVERRIDES = 256;

    private final VersionedTypesHolder types;
    private final ProtocolVersion version;
    /** 26.x sub-item templates read the id before the amount and have no empty short-circuit. */
    private final boolean templateFraming;
    /** 26.x optional templates carry an explicit present flag; earlier schemas fold it into the amount. */
    private final boolean optionalTemplate;

    private int depth;
    private int items;
    private int components;

    private ItemStructureGuard(VersionedTypesHolder types, ProtocolVersion version) {
        this.types = types;
        this.version = version;
        templateFraming = types.itemTemplate() != types.item();
        optionalTemplate = types.optionalItemTemplate() != types.itemTemplate();
    }

    /** One stack in the framing of {@code item()}/{@code lengthPrefixedItem()} for the schema. */
    static void scanItemStack(
            VersionedTypesHolder types, ProtocolVersion version, ByteBuf input, boolean lengthPrefixed) {
        new ItemStructureGuard(types, version).item(input, lengthPrefixed, false);
    }

    /** One cost in the framing of {@code itemCost()}/{@code optionalItemCost()} for the schema. */
    static void scanItemCost(VersionedTypesHolder types, ProtocolVersion version, ByteBuf input, boolean optional) {
        var guard = new ItemStructureGuard(types, version);
        if (optional && !input.readBoolean()) return;
        guard.cost(input);
    }

    /**
     * One item in stack framing ({@code amount, id, data}) as read at packet level, or in 26.x
     * template framing ({@code id, amount, data}) as read for sub-items.
     */
    private void item(ByteBuf input, boolean lengthPrefixed, boolean template) {
        if (++items > MAX_ITEMS) throw limit("item count exceeds " + MAX_ITEMS);
        enter();
        try {
            if (template) {
                Wire.readVarInt(input); // Item identifier.
                Wire.readVarInt(input); // Template amount, which has no empty short-circuit.
            } else {
                if (Wire.readVarInt(input) <= 0) return; // ItemType1_20_5 empty short-circuit.
                Wire.readVarInt(input); // Item identifier.
            }
            data(input, lengthPrefixed);
        } finally {
            depth--;
        }
    }

    private void cost(ByteBuf input) {
        if (++items > MAX_ITEMS) throw limit("item count exceeds " + MAX_ITEMS);
        enter();
        try {
            Wire.readVarInt(input); // Item identifier.
            Wire.readVarInt(input); // Amount, which item costs allow to be non-positive.
            int count = count(input, "item cost components", MAX_COMPONENTS_PER_ITEM);
            for (int index = 0; index < count; index++) component(input, false);
        } finally {
            depth--;
        }
    }

    /** {@code ItemType1_20_5.readData}: value components then removal markers. */
    private void data(ByteBuf input, boolean lengthPrefixed) {
        int values = Wire.readVarInt(input);
        int markers = Wire.readVarInt(input);
        if (values < 0 || markers < 0) throw new MalformedPacketException("Negative item component count");
        if ((long) values + markers > MAX_COMPONENTS_PER_ITEM)
            throw limit("item component count " + ((long) values + markers) + " exceeds " + MAX_COMPONENTS_PER_ITEM);
        for (int index = 0; index < values; index++) component(input, lengthPrefixed);
        for (int index = 0; index < markers; index++) {
            chargeComponent();
            Wire.readVarInt(input); // Removal markers carry no payload.
        }
    }

    private void component(ByteBuf input, boolean lengthPrefixed) {
        chargeComponent();
        int id = Wire.readVarInt(input);
        var key = types.structuredData().key(id);
        // The readers reject an unknown id too; the scan cannot size its payload.
        if (key == null) throw new MalformedPacketException("Unknown data component " + id);
        if (lengthPrefixed) {
            int size = Wire.readVarInt(input);
            if (size < 0 || size > input.readableBytes())
                throw new MalformedPacketException("Invalid component payload size " + size);
            payload(input.readSlice(size), key);
        } else {
            payload(input, key);
        }
    }

    /** Recurses into the components that embed further items; every other payload is skipped. */
    private void payload(ByteBuf input, StructuredDataKey<?> key) {
        switch (Key.stripMinecraftNamespace(key.identifier())) {
            case "container" -> container(input);
            case "charged_projectiles", "bundle_contents" -> itemArray(input);
            case "use_remainder", "sulfur_cube_content" -> element(input, false);
            case "pot_decorations" -> {
                // Only 26.3 decorations are item templates; earlier schemas send an id array.
                if (version.atLeast(ProtocolVersion.V26_3)) {
                    for (int index = 0; index < 4; index++) element(input, true);
                } else {
                    key.type().read(input);
                }
            }
            case "can_place_on", "can_break" -> {
                // 1.21.2/1.21.4 predicates embed no structured data; only the matchers schema recurses.
                if (version.atLeast(ProtocolVersion.V1_21_5)) predicates(input);
                else key.type().read(input);
            }
            case "painting/variant" -> paintingVariant(input);
            case "trim", "provides_trim_material" -> {
                trimOverrides(input, key);
                key.type().read(input);
            }
            default -> key.type().read(input);
        }
    }

    /**
     * {@code PaintingVariant.TYPE1_21_2} reads the inline title and author through
     * {@code Types.TRUSTED_OPTIONAL_TAG}, whose limiter has no byte budget: an array claim
     * just below {@code Integer.MAX_VALUE} is counted in long arithmetic without throwing and
     * is allocated before the missing payload fails the read. The scan validates the identical
     * framing with the bounded skipper, which rejects any claim larger than the remaining frame
     * before the decoder's trusted reader can allocate it.
     */
    private void paintingVariant(ByteBuf input) {
        if (Wire.readVarInt(input) != 0) return; // Registry reference; no inline payload.
        Wire.readVarInt(input); // Width.
        Wire.readVarInt(input); // Height.
        Types.STRING.read(input); // Asset identifier; StringType bounds the allocation.
        optionalTag(input); // Title.
        optionalTag(input); // Author.
    }

    private static void optionalTag(ByteBuf input) {
        if (input.readBoolean()) NbtSkipper.skip(input, MAX_NBT_DEPTH);
    }

    /**
     * Refuses a direct trim material in {@code value}, a {@code key} component, with more armour overrides than
     * {@link #MAX_TRIM_OVERRIDES} before Via reads it: its reader puts each override into an array map, whose every
     * insert scans the keys before it. Reads a duplicate of {@code value}.
     */
    static void trimOverrides(ByteBuf value, StructuredDataKey<?> key) {
        boolean ingredient = key == StructuredDataKey.TRIM1_21_2 || key == StructuredDataKey.TRIM1_21_4;
        if (!ingredient
                && key != StructuredDataKey.TRIM1_21_5
                && key != StructuredDataKey.PROVIDES_TRIM_MATERIAL1_21_5
                && key != StructuredDataKey.PROVIDES_TRIM_MATERIAL26_1) return;
        ByteBuf input = value.duplicate();
        if (key == StructuredDataKey.PROVIDES_TRIM_MATERIAL1_21_5 && !input.readBoolean()) return; // A registry key.
        if (Wire.readVarInt(input) != 0) return; // A registry reference.
        Types.STRING.read(input); // Asset.
        if (ingredient) Wire.readVarInt(input);
        if (key == StructuredDataKey.TRIM1_21_2) input.readFloat(); // Item model index.
        int overrides = Wire.readVarInt(input);
        if (overrides < 0) throw new MalformedPacketException("Negative trim material overrides");
        if (overrides > MAX_TRIM_OVERRIDES)
            throw limit("trim material overrides " + overrides + " exceed " + MAX_TRIM_OVERRIDES);
    }

    private void container(ByteBuf input) {
        int count = count(input, "container slots", MAX_CONTAINER_SLOTS);
        for (int index = 0; index < count; index++) element(input, true);
    }

    private void itemArray(ByteBuf input) {
        int count = count(input, "item array entries", MAX_ITEM_ARRAY);
        for (int index = 0; index < count; index++) element(input, false);
    }

    /** One {@code itemTemplate()} element; older schemas use the plain stack framing. */
    private void element(ByteBuf input, boolean optional) {
        if (optional && optionalTemplate && !input.readBoolean()) return;
        item(input, false, templateFraming);
    }

    /** {@code AdventureModePredicateType1_21_5}: block predicates whose matchers embed components. */
    private void predicates(ByteBuf input) {
        int count = count(input, "block predicates", MAX_BLOCK_PREDICATES);
        for (int index = 0; index < count; index++) {
            Types.OPTIONAL_HOLDER_SET.read(input);
            if (input.readBoolean()) StatePropertyMatcher.ARRAY_TYPE.read(input);
            Types.OPTIONAL_COMPOUND_TAG.read(input);
            int exact = count(input, "exact predicates", MAX_EXACT_PREDICATES);
            for (int nested = 0; nested < exact; nested++) exactPredicate(input);
            // Partial predicates are a type id and a limited NBT tag; no items are embedded.
            (version.atLeast(ProtocolVersion.V1_21_11)
                            ? DataComponentPredicate.ARRAY_TYPE1_21_11
                            : DataComponentPredicate.ARRAY_TYPE1_21_5)
                    .read(input);
        }
    }

    private void exactPredicate(ByteBuf input) {
        enter();
        try {
            component(input, false);
        } finally {
            depth--;
        }
    }

    private int count(ByteBuf input, String what, int maximum) {
        int count = Wire.readVarInt(input);
        if (count < 0) throw new MalformedPacketException("Negative " + what);
        if (count > maximum) throw limit(what + " " + count + " exceeds " + maximum);
        return count;
    }

    private void chargeComponent() {
        if (++components > MAX_COMPONENTS) throw limit("component count exceeds " + MAX_COMPONENTS);
    }

    private void enter() {
        if (++depth > MAX_DEPTH) throw limit("structure depth exceeds " + MAX_DEPTH);
    }

    private static PacketResourceLimitException limit(String detail) {
        return new PacketResourceLimitException("Item structure exceeds the decode budget: " + detail);
    }
}
