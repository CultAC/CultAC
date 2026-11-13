package ac.cult.cultac.network.packet;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.RandomAccess;
import java.util.function.Supplier;

/** Owned inventory values decoded by the versioned protocol component codecs. */
public final class InventoryPackets {
    private InventoryPackets() {}

    public record CreativeSlot(int slot, SimItemStack item) implements ServerboundPacket {}

    public record Content(int windowId, int stateId, List<SimItemStack> items, SimItemStack carriedItem)
            implements ClientboundPacket {
        public Content {
            items = List.copyOf(items);
        }
    }

    public record Slot(int windowId, int stateId, int slot, SimItemStack item) implements ClientboundPacket {}

    public record PlayerInventory(int slot, SimItemStack item) implements ClientboundPacket {}

    public record Cursor(SimItemStack item) implements ClientboundPacket {}

    /** Equipment values remain owned by the packet; consumers copy stacks before predicting changes. */
    public record EquipmentEntry(EquipmentSlot slot, SimItemStack item) {}

    /** {@code slots} may be a {@link SentEquipment}, which one thread at a time reads. */
    public record Equipment(int entityId, List<EquipmentEntry> slots) implements ClientboundPacket {
        public Equipment {
            if (!(slots instanceof SentEquipment)) slots = List.copyOf(slots);
        }

        /** The slot of entry {@code index} of {@code slots}, without reading its stack. */
        public static EquipmentSlot slot(List<EquipmentEntry> slots, int index) {
            return slots instanceof SentEquipment sent ? sent.slot(index) : slots.get(index).slot();
        }
    }

    /**
     * Immutable equipment entries whose stacks are supplied on their first read, which returns the same entry every
     * time. One thread at a time reads them.
     */
    public static final class SentEquipment extends AbstractList<EquipmentEntry> implements RandomAccess {
        private final EquipmentSlot[] slots;
        private final Object[] entries;

        /** Adopts {@code slots}; {@code items} holds a non-null supplier for each slot, at the same index. */
        public SentEquipment(EquipmentSlot[] slots, Supplier<SimItemStack>[] items) {
            if (items.length < slots.length) throw new IllegalArgumentException("Equipment slots outnumber items");
            this.slots = slots;
            this.entries = Arrays.copyOf(items, slots.length, Object[].class);
        }

        public EquipmentSlot slot(int index) {
            return slots[index];
        }

        @Override
        @SuppressWarnings("unchecked")
        public EquipmentEntry get(int index) {
            Object entry = entries[index];
            if (entry instanceof EquipmentEntry read) return read;
            var read = new EquipmentEntry(slots[index], ((Supplier<SimItemStack>) entry).get());
            entries[index] = read;
            return read;
        }

        @Override
        public int size() {
            return slots.length;
        }
    }

    public record MerchantOffer(SimItemStack costA, SimItemStack costB, SimItemStack result, boolean outOfStock) {}

    public record Offers(int windowId, List<MerchantOffer> offers) implements ClientboundPacket {
        public Offers {
            offers = List.copyOf(offers);
        }
    }
}
