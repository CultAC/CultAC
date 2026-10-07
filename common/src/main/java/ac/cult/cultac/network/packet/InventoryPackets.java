package ac.cult.cultac.network.packet;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import java.util.List;

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
    public record EquipmentEntry(EquipmentSlot slot, ac.cult.blocksim.engine.SimItemStack item) {}

    public record Equipment(int entityId, List<EquipmentEntry> slots) implements ClientboundPacket {
        public Equipment {
            slots = List.copyOf(slots);
        }
    }

    public record MerchantOffer(SimItemStack costA, SimItemStack costB, SimItemStack result, boolean outOfStock) {}

    public record Offers(int windowId, List<MerchantOffer> offers) implements ClientboundPacket {
        public Offers {
            offers = List.copyOf(offers);
        }
    }
}
