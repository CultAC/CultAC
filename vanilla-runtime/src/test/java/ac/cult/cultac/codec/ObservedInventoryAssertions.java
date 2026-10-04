package ac.cult.cultac.codec;

import static ac.cult.cultac.codec.ObservedValueAssertions.read;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.network.codec.ObservedPacketValues;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.inventory.InventoryClick;
import com.viaversion.viaversion.api.minecraft.item.*;
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

/** Exact Via item codecs inside every consumed inventory packet's physical framing. */
final class ObservedInventoryAssertions {
    private ObservedInventoryAssertions() {}

    static void verify(
            ObservedPacketValues values,
            RegistryAccess access,
            ProtocolVersion version,
            ModelRegistryData source,
            ByteBuf input) {
        var types = PrivateCodecService.types(version);
        int stoneId = source.registry("minecraft:item").id("minecraft:stone");
        var stone = new StructuredItem(stoneId, 3);
        var dirt = new StructuredItem(source.registry("minecraft:item").id("minecraft:dirt"), 1);

        input.clear();
        Wire.writeVarInt(input, 257);
        Wire.writeVarInt(input, 129);
        Wire.writeVarInt(input, 2);
        types.item().write(input, stone);
        types.item().write(input, dirt);
        types.item().write(input, new StructuredItem(0, 0));
        var content = (InventoryPackets.Content)
                read(values, access, version, ConnectionPhase.PLAY, "container_set_content", input);
        assertEquals(257, content.windowId());
        assertEquals(129, content.stateId());
        assertEquals(2, content.items().size());
        assertSame(Items.STONE, content.items().getFirst().getItem());
        assertEquals(3, content.items().getFirst().getCount());
        assertSame(Items.DIRT, content.items().getLast().getItem());
        assertTrue(content.carriedItem().isEmpty());
        assertFalse(input.isReadable());

        input.clear();
        Wire.writeVarInt(input, 257);
        Wire.writeVarInt(input, 129);
        input.writeShort(4);
        types.item().write(input, stone);
        var slot = (InventoryPackets.Slot)
                read(values, access, version, ConnectionPhase.PLAY, "container_set_slot", input);
        assertEquals(257, slot.windowId());
        assertEquals(129, slot.stateId());
        assertEquals(4, slot.slot());
        assertSame(Items.STONE, slot.item().getItem());
        assertFalse(input.isReadable());

        input.clear();
        Wire.writeVarInt(input, 36);
        types.item().write(input, dirt);
        var inventory = (InventoryPackets.PlayerInventory)
                read(values, access, version, ConnectionPhase.PLAY, "set_player_inventory", input);
        assertEquals(36, inventory.slot());
        assertSame(Items.DIRT, inventory.item().getItem());
        assertFalse(input.isReadable());

        input.clear();
        Wire.writeVarInt(input, 0);
        input.writeByte(128); // First equipment slot continues the list.
        types.item().write(input, stone);
        input.writeByte(1);
        types.item().write(input, dirt);
        var equipment = (InventoryPackets.Equipment)
                read(values, access, version, ConnectionPhase.PLAY, "set_equipment", input);
        assertEquals(0, equipment.entityId());
        assertEquals(2, equipment.slots().size());
        assertSame(EquipmentSlot.MAINHAND, equipment.slots().getFirst().getFirst());
        assertSame(EquipmentSlot.OFFHAND, equipment.slots().getLast().getFirst());
        assertSame(Items.STONE, equipment.slots().getFirst().getSecond().getItem());
        assertSame(Items.DIRT, equipment.slots().getLast().getSecond().getItem());
        assertFalse(input.isReadable());

        input.clear();
        Wire.writeVarInt(input, 257);
        Wire.writeVarInt(input, 1);
        types.itemCost().write(input, stone);
        types.item().write(input, dirt);
        types.optionalItemCost().write(input, null);
        input.writeBoolean(true)
                .writeInt(2)
                .writeInt(3)
                .writeInt(5)
                .writeInt(-1)
                .writeFloat(.5f)
                .writeInt(2);
        Wire.writeVarInt(input, 7);
        Wire.writeVarInt(input, 26);
        input.writeBoolean(true).writeBoolean(false);
        var offers =
                (InventoryPackets.Offers) read(values, access, version, ConnectionPhase.PLAY, "merchant_offers", input);
        assertEquals(257, offers.windowId());
        assertEquals(1, offers.offers().size());
        var offer = offers.offers().getFirst();
        assertSame(Items.STONE, offer.costA().getItem());
        assertEquals(5, offer.costA().getCount());
        assertTrue(offer.costB().isEmpty());
        assertSame(Items.DIRT, offer.result().getItem());
        assertTrue(offer.outOfStock());
        assertEquals(4, input.readableBytes(), "The unconsumed merchant UI fields keep their native framing");

        input.clear();
        Wire.writeVarInt(input, 257);
        Wire.writeVarInt(input, 129);
        input.writeShort(4).writeByte(0);
        Wire.writeVarInt(input, 0);
        Wire.writeVarInt(input, 1);
        input.writeShort(4);
        if (version.atLeast(ProtocolVersion.V1_21_5)) {
            Types.HASHED_ITEM.write(input, new HashedStructuredItem(stoneId, 3));
            Types.HASHED_ITEM.write(input, HashedStructuredItem.empty());
        } else {
            types.item().write(input, stone);
            types.item().write(input, new StructuredItem(0, 0));
        }
        var click = (InventoryClick) read(values, access, version, ConnectionPhase.PLAY, "container_click", input);
        assertEquals(257, click.windowId());
        assertEquals(129, click.stateId());
        assertEquals(4, click.slot());
        assertSame(Items.STONE, click.changedSlots().get(4).getItem());
        assertEquals(3, click.changedSlots().get(4).getCount());
        assertTrue(click.carriedItem().isEmpty());
        assertFalse(input.isReadable());
    }
}
