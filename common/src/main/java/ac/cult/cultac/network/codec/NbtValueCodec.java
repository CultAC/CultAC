package ac.cult.cultac.network.codec;

import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.wire.NbtSkipper;
import ac.cult.cultac.utils.latency.ClientBookFields;
import ac.cult.cultac.utils.latency.ClientContainerFields;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Nullable world NBT, using the existing PacketEvents-derived framing and owned values. */
public final class NbtValueCodec {
    private static final NbtValue.Compound EMPTY = new NbtValue.Compound(Map.of());

    private NbtValueCodec() {}

    /** Block packets cannot be re-encoded: retain only prediction inputs. */
    static NbtValue.Compound readBlockEntity(ByteBuf input, String type) {
        Set<String> fields = switch (type) {
            case "minecraft:comparator" -> Set.of("OutputSignal");
            case "minecraft:potent_sulfur" -> Set.of("countdown");
            case "minecraft:command_block" -> Set.of("SuccessCount");
            case "minecraft:sculk_sensor", "minecraft:calibrated_sculk_sensor" -> Set.of("last_vibration_frequency");
            case "minecraft:lectern" -> Set.of("Book", "Page");
            case "minecraft:sign", "minecraft:hanging_sign" -> Set.of("front_text", "back_text", "is_waxed");
            case "minecraft:test_block" -> Set.of("powered");
            case "minecraft:jukebox" -> Set.of("RecordItem", "ticks_since_song_started");
            case "minecraft:piston" -> Set.of("blockState", "facing", "progress", "extending", "source");
            case "minecraft:decorated_pot" -> Set.of("item", "LootTable");
            case "minecraft:crafter" -> Set.of("Items", "LootTable", "disabled_slots");
            case "minecraft:chest",
                    "minecraft:trapped_chest",
                    "minecraft:barrel",
                    "minecraft:shulker_box",
                    "minecraft:dispenser",
                    "minecraft:dropper",
                    "minecraft:hopper" -> Set.of("Items", "LootTable");
            case "minecraft:brewing_stand", "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker" ->
                Set.of("Items");
            default -> Set.of();
        };
        int rootType = input.readUnsignedByte();
        if (rootType == 0) return null;
        if (rootType != 10) throw new MalformedPacketException("Expected compound NBT");
        if (fields.isEmpty()) {
            NbtSkipper.skipPayload(input, rootType, 0, 512);
            return EMPTY;
        }
        var retained = new HashMap<String, NbtValue>();
        var stream = new ByteBufInputStream(input);
        int childType;
        try {
            while ((childType = input.readUnsignedByte()) != 0) {
                String name = stream.readUTF();
                if (!fields.contains(name)) {
                    NbtSkipper.skipPayload(input, childType, 1, 512);
                } else if (name.equals("Items") && childType == 9) {
                    retained.put(name, readContainerItems(input));
                } else if (name.equals("item") && childType == 10) {
                    retained.put(name, readContainerItem(input, 1, false));
                } else if (name.equals("Book") && childType == 10) {
                    retained.put(name, readContainerItem(input, 1, false, true));
                } else retained.put(name, readValue(input, childType, 1));
            }
        } catch (java.io.IOException failure) {
            throw new MalformedPacketException("Invalid NBT field name", failure);
        }
        return retained.isEmpty() ? EMPTY : new NbtValue.Compound(retained);
    }

    private static NbtValue.Sequence readContainerItems(ByteBuf input) throws java.io.IOException {
        int elementType = input.readUnsignedByte(), count = input.readInt();
        if (count < 0 || elementType == 0 && count > 0) throw new MalformedPacketException("Invalid NBT list");
        var items = new ArrayList<NbtValue>();
        for (int i = 0; i < count; i++) {
            if (elementType == 10) items.add(readContainerItem(input, 2, true));
            else NbtSkipper.skipPayload(input, elementType, 2, 512);
        }
        return new NbtValue.Sequence(items);
    }

    private static NbtValue.Compound readContainerItem(ByteBuf input, int depth, boolean listElement)
            throws java.io.IOException {
        return readContainerItem(input, depth, listElement, false);
    }

    private static NbtValue.Compound readContainerItem(ByteBuf input, int depth, boolean listElement, boolean book)
            throws java.io.IOException {
        if (depth >= 512) throw new MalformedPacketException("NBT depth exceeds 512");
        var fields = new HashMap<String, NbtValue>();
        var stream = new ByteBufInputStream(input);
        var wrapped = EMPTY;
        boolean onlyWrapper = listElement;
        int type;
        while ((type = input.readUnsignedByte()) != 0) {
            String name = stream.readUTF();
            onlyWrapper &= name.isEmpty();
            if (listElement && name.isEmpty() && type == 10) {
                wrapped = readContainerItem(input, depth + 1, false);
            } else if (name.equals("components") && type == 10) {
                fields.put(name, readItemComponents(input, depth + 1, book));
            } else if (name.equals("id") && type == 8
                    || (name.equals("count") || !book && name.equals("Slot")) && type >= 1 && type <= 6) {
                fields.put(name, readValue(input, type, depth + 1));
            } else {
                NbtSkipper.skipPayload(input, type, depth + 1, 512);
                if (book) fields.remove(name);
                if (name.isEmpty()) wrapped = EMPTY;
            }
        }
        // Model-version heterogeneous lists wrap each value in a single empty-name field.
        return onlyWrapper ? wrapped : fields.isEmpty() ? EMPTY : new NbtValue.Compound(fields);
    }

    private static NbtValue.Compound readItemComponents(ByteBuf input, int depth, boolean book)
            throws java.io.IOException {
        if (depth >= 512) throw new MalformedPacketException("NBT depth exceeds 512");
        // Key order still decides duplicate namespace aliases. Keep the names until
        // projection, but skip every unused payload without building its NBT tree.
        var fields = new LinkedHashMap<String, NbtValue>();
        var ignored = new NbtValue.Text("");
        var stream = new ByteBufInputStream(input);
        int type;
        while ((type = input.readUnsignedByte()) != 0) {
            String name = stream.readUTF();
            boolean stackLimit = !book && ClientContainerFields.isStackLimitComponent(name);
            NbtValue value = ignored;
            if (book && ClientBookFields.isContentComponent(name) && type == 10) {
                if (name.startsWith("!")) NbtSkipper.skipPayload(input, type, depth + 1, 512);
                else value = readBookContent(input, depth + 1);
            } else if (stackLimit && !name.startsWith("!") && type >= 1 && type <= 6) {
                value = readValue(input, type, depth + 1);
            } else {
                NbtSkipper.skipPayload(input, type, depth + 1, 512);
                if (stackLimit && name.startsWith("!") && type == 10) value = EMPTY;
            }
            fields.put(name, value);
        }
        var components = new NbtValue.Compound(fields);
        return book
                ? ClientBookFields.contentComponents(components)
                : ClientContainerFields.stackLimitComponents(components);
    }

    private static NbtValue.Compound readBookContent(ByteBuf input, int depth) throws java.io.IOException {
        if (depth >= 512) throw new MalformedPacketException("NBT depth exceeds 512");
        var fields = new HashMap<String, NbtValue>();
        var stream = new ByteBufInputStream(input);
        int type;
        while ((type = input.readUnsignedByte()) != 0) {
            String name = stream.readUTF();
            if (name.equals("pages") && type == 9) {
                int elementType = input.readUnsignedByte(), count = input.readInt();
                if (count < 0 || elementType == 0 && count > 0) throw new MalformedPacketException("Invalid NBT list");
                for (int index = 0; index < count; index++) NbtSkipper.skipPayload(input, elementType, depth + 2, 512);
                // LecternBlockEntity.getPageCount reads only the list size. Share
                // empty values instead of retaining text, components or filter data.
                fields.put(name, new NbtValue.Sequence(java.util.Collections.nCopies(count, EMPTY)));
            } else {
                NbtSkipper.skipPayload(input, type, depth + 1, 512);
                if (name.equals("pages")) fields.remove(name);
            }
        }
        return fields.isEmpty() ? EMPTY : new NbtValue.Compound(fields);
    }

    private static NbtValue readValue(ByteBuf input, int type, int depth) {
        int start = input.readerIndex();
        NbtSkipper.skipPayload(input, type, depth, 512);
        byte[] bytes = new byte[1 + input.readerIndex() - start];
        bytes[0] = (byte) type;
        input.getBytes(start, bytes, 1, bytes.length - 1);
        return BinaryNbt.read(bytes, 2097152L);
    }

    public static NbtValue.Compound readCompound(ByteBuf input) {
        int start = input.readerIndex();
        NbtSkipper.skip(input, 512);
        if (input.getUnsignedByte(start) == 0) return null;
        byte[] bytes = new byte[input.readerIndex() - start];
        input.getBytes(start, bytes);
        // FriendlyByteBuf.readNbt uses the default 2 MiB accounted-heap quota.
        var tag = BinaryNbt.read(bytes, 2097152L);
        if (tag instanceof NbtValue.Compound compound) return compound;
        throw new MalformedPacketException("Expected compound NBT");
    }
}
