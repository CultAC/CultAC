package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Slot, count and stack-limit inputs to comparator prediction. */
public final class ClientContainerFields {
    private static final java.util.regex.Pattern REFERENCE =
            java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]*");
    private static final ac.cult.cultac.protocol.data.IdTable ITEMS =
            ModelRegistryData.load(ProtocolVersion.V26_3).registry("minecraft:item");
    private static final NbtValue.Compound EMPTY = new NbtValue.Compound(Map.of());

    private ClientContainerFields() {}

    static NbtValue.Compound load(String type, NbtValue.Compound tag) {
        if (type.equals("minecraft:decorated_pot")) {
            var item = reference(tag.values().get("LootTable")) == null
                    ? item(tag.values().get("item"))
                    : null;
            return item == null ? EMPTY : new NbtValue.Compound(Map.of("item", item));
        }
        int size = switch (type) {
            case "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:shulker_box" -> 27;
            case "minecraft:dispenser", "minecraft:dropper", "minecraft:crafter" -> 9;
            case "minecraft:hopper", "minecraft:brewing_stand" -> 5;
            case "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker" -> 3;
            default -> 0;
        };
        if (size == 0) return EMPTY;
        boolean randomizable = !List.of(
                        "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker", "minecraft:brewing_stand")
                .contains(type);
        var slots = new TreeMap<Integer, NbtValue>();
        if ((!randomizable || reference(tag.values().get("LootTable")) == null)
                && tag.values().get("Items") instanceof NbtValue.Sequence items) {
            for (var value : items.values()) {
                if (!(value instanceof NbtValue.Compound input)) continue;
                var slotValue = input.values().get("Slot");
                int slot = slotValue instanceof NbtValue.Numeric number
                        ? Byte.toUnsignedInt(number.value().byteValue())
                        : 0;
                if (slot >= size) continue;
                var item = item(input);
                if (item == null) continue;
                var fields = new HashMap<>(item.values());
                fields.put("Slot", new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte) slot));
                slots.put(slot, new NbtValue.Compound(fields));
            }
        }
        var saved = new HashMap<String, NbtValue>();
        saved.put("Items", new NbtValue.Sequence(List.copyOf(slots.values())));
        if (type.equals("minecraft:crafter")) {
            int disabled = 0;
            if (tag.values().get("disabled_slots") instanceof NbtValue.PrimitiveArray array
                    && array.kind() == NbtValue.Kind.INT_ARRAY)
                for (var index : array.values()) {
                    int slot = index.intValue();
                    if (slot >= 0 && slot < size && !slots.containsKey(slot)) disabled |= 1 << slot;
                }
            var indices = new java.util.ArrayList<Long>(Integer.bitCount(disabled));
            for (int slot = 0; slot < size; slot++) if ((disabled & 1 << slot) != 0) indices.add((long) slot);
            saved.put("disabled_slots", new NbtValue.PrimitiveArray(NbtValue.Kind.INT_ARRAY, indices));
        }
        return new NbtValue.Compound(saved);
    }

    /** A block inventory is never transferred to the predicted player inventory. */
    static NbtValue.Compound item(NbtValue value) {
        if (!(value instanceof NbtValue.Compound input)) return null;
        String id = reference(input.values().get("id"));
        if (id == null || id.equals("minecraft:air") || ITEMS.id(id) < 0) return null;
        int count = input.values().get("count") instanceof NbtValue.Numeric number
                ? number.value().intValue()
                : 1;
        if (count < 1 || count > 99) count = 1;
        var fields = new HashMap<String, NbtValue>();
        fields.put("id", new NbtValue.Text(id));
        fields.put("count", new NbtValue.Numeric(NbtValue.Kind.INT, count));
        if (input.values().get("components") instanceof NbtValue.Compound components) {
            var patch = stackLimitComponents(components);
            if (!patch.values().isEmpty()) fields.put("components", patch);
        }
        return new NbtValue.Compound(fields);
    }

    /** Shared with the packet reader so unused component payloads never need decoding. */
    public static NbtValue.Compound stackLimitComponents(NbtValue.Compound components) {
        var patch = new HashMap<String, NbtValue>();
        for (var entry : components.values().entrySet()) {
            String key = entry.getKey();
            boolean removed = key.startsWith("!");
            if (!isStackLimitComponent(key)) continue;
            if (removed && entry.getValue() instanceof NbtValue.Compound) {
                patch.clear();
                patch.put("!minecraft:max_stack_size", EMPTY);
            } else if (!removed
                    && entry.getValue() instanceof NbtValue.Numeric number
                    && number.value().intValue() >= 1
                    && number.value().intValue() <= 99) {
                patch.clear();
                patch.put(
                        "minecraft:max_stack_size",
                        new NbtValue.Numeric(NbtValue.Kind.INT, number.value().intValue()));
            }
        }
        return patch.isEmpty() ? EMPTY : new NbtValue.Compound(patch);
    }

    public static boolean isStackLimitComponent(String key) {
        return "minecraft:max_stack_size"
                .equals(reference(new NbtValue.Text(key.startsWith("!") ? key.substring(1) : key)));
    }

    /** Resource-key codecs normalize the namespace; they do not resolve a registry entry. */
    static String reference(NbtValue value) {
        if (!(value instanceof NbtValue.Text text)) return null;
        String key = text.value();
        int separator = key.indexOf(':');
        key = separator < 0 ? "minecraft:" + key : separator == 0 ? "minecraft" + key : key;
        return !key.startsWith("..:") && REFERENCE.matcher(key).matches() ? key : null;
    }
}
