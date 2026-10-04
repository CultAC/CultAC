package ac.cult.cultac.network.codec;

import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.ByteBuf;
import java.util.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** Versioned inventory framing around the isolated item value readers. */
final class ObservedInventoryValues {
    private final ObservedPacketValues values;

    ObservedInventoryValues(ObservedPacketValues values) {
        this.values = values;
    }

    Object read(ByteBuf input, String name) {
        return switch (name) {
            case "minecraft:set_creative_mode_slot" ->
                new InventoryPackets.CreativeSlot(input.readShort(), values.item(input, true));
            case "minecraft:container_set_content" -> {
                int window = Wire.readVarInt(input), state = Wire.readVarInt(input);
                int count = Wire.readLength(input, input.readableBytes());
                var items = new ArrayList<ItemStack>(count);
                for (int slot = 0; slot < count; slot++) items.add(values.item(input, false));
                yield new InventoryPackets.Content(window, state, items, values.item(input, false));
            }
            case "minecraft:container_set_slot" ->
                new InventoryPackets.Slot(
                        Wire.readVarInt(input), Wire.readVarInt(input), input.readShort(), values.item(input, false));
            case "minecraft:set_player_inventory" ->
                new InventoryPackets.PlayerInventory(Wire.readVarInt(input), values.item(input, false));
            case "minecraft:set_cursor_item" -> new InventoryPackets.Cursor(values.item(input, false));
            case "minecraft:set_equipment" -> equipment(input);
            case "minecraft:container_click" -> click(input);
            case "minecraft:merchant_offers" -> offers(input);
            default -> throw new ProtocolResolutionException("No observed value reader for " + name);
        };
    }

    private InventoryPackets.Equipment equipment(ByteBuf input) {
        int id = Wire.readVarInt(input), slot;
        var entries = new ArrayList<Pair<EquipmentSlot, ItemStack>>();
        do {
            slot = input.readUnsignedByte();
            entries.add(Pair.of(EquipmentSlot.VALUES.get(slot & 127), values.item(input, false)));
        } while ((slot & 128) != 0);
        return new InventoryPackets.Equipment(id, entries);
    }

    private InventoryClick click(ByteBuf input) {
        int window = Wire.readVarInt(input), state = Wire.readVarInt(input);
        int slot = input.readShort(), button = input.readByte(), mode = Wire.readVarInt(input);
        if (mode < 0 || mode >= WindowClickType.VALUES.length)
            throw new MalformedPacketException("Unknown click mode " + mode);
        int count = Wire.readLength(input, 128);
        var changed = new HashMap<Integer, ItemStack>();
        for (int index = 0; index < count; index++) changed.put((int) input.readShort(), clickItem(input));
        return new InventoryClick(window, state, slot, button, WindowClickType.VALUES[mode], changed, clickItem(input));
    }

    private ItemStack clickItem(ByteBuf input) {
        return values.version().atLeast(ProtocolVersion.V1_21_5)
                ? values.item(values.decoder().hashedItem(values.version(), input))
                : values.item(input, false);
    }

    private InventoryPackets.Offers offers(ByteBuf input) {
        int window = Wire.readVarInt(input), count = Wire.readLength(input, input.readableBytes());
        var offers = new ArrayList<InventoryPackets.MerchantOffer>(count);
        for (int index = 0; index < count; index++) {
            var costA = values.item(values.decoder().itemCost(values.version(), input, false, values.names()));
            var result = values.item(input, false);
            var costB = values.item(values.decoder().itemCost(values.version(), input, true, values.names()));
            boolean out = input.readBoolean();
            input.readInt();
            input.readInt();
            input.readInt(); // uses, maximum uses, XP
            int special = input.readInt();
            float multiplier = input.readFloat();
            int demand = input.readInt();
            int adjusted =
                    costA.getCount() + Math.max(0, (int) Math.floor(costA.getCount() * demand * multiplier)) + special;
            costA.setCount(Math.max(1, Math.min(adjusted, costA.getMaxStackSize())));
            offers.add(new InventoryPackets.MerchantOffer(costA, costB, result, out));
        }
        // Level, XP and UI/restock flags are not consumed, matching NativePacketCodecs.
        return new InventoryPackets.Offers(window, offers);
    }
}
