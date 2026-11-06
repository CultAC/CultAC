package ac.cult.cultac.network.codec;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.entity.PaintingSize;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.FrameLease;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * Consumed metadata uses owned values; every entry retains its original wire bytes, borrowed from a lent frame or
 * copied from any other input.
 */
final class ModelEntityMetadataValues {
    private static final class Defaults {
        private static final ItemRegistry ITEMS = new ItemRegistry(DataTables.defaults());
    }

    private final WireValueDecoder decoder;
    private final ProtocolVersion version;
    private final WireValueDecoder.Registries registries;
    private final ModelItemValues items;
    private final Function<String, PaintingSize> paintings;
    private Scratch scratch;

    ModelEntityMetadataValues(
            WireValueDecoder decoder,
            ProtocolVersion version,
            WireValueDecoder.Registries registries,
            Function<String, PaintingSize> paintings) {
        this.decoder = decoder;
        this.version = version;
        this.registries = registries;
        this.items = new ModelItemValues(decoder, version, registries, Defaults.ITEMS);
        this.paintings = paintings;
    }

    /** As {@link #read(ByteBuf, FrameLease)}, copying the entries' bytes. */
    EntityMetadata read(ByteBuf input) {
        return read(input, null);
    }

    /**
     * Null when a painting variant needs world registries this connection lacks. With a {@code lease} on
     * {@code input}, the entries borrow its bytes for the lease's lifetime.
     */
    EntityMetadata read(ByteBuf input, FrameLease lease) {
        int id = Wire.readVarInt(input);
        int payloadStart = input.readerIndex();
        var scratch = this.scratch == null ? this.scratch = new Scratch() : this.scratch;
        try {
            decoder.metadata(version, input, registries, ModelNbtFactory.INSTANCE, scratch);
            int size = scratch.size;
            int payloadLength =
                    size == 0 ? 0 : scratch.offsets[size - 1] + scratch.lengths[size - 1] - payloadStart;
            byte[] payload = lease == null ? ByteBufUtil.getBytes(input, payloadStart, payloadLength) : null;
            if (size == 0) return new EntityMetadata(id, List.of());
            var first = scratch.entry(this, 0, payload, payloadStart, lease);
            if (first == null) return null;
            if (size == 1) return new EntityMetadata(id, List.of(first));
            var second = scratch.entry(this, 1, payload, payloadStart, lease);
            if (second == null) return null;
            if (size == 2) return new EntityMetadata(id, List.of(first, second));
            var entries = new EntityMetadata.Entry[size];
            entries[0] = first;
            entries[1] = second;
            for (int i = 2; i < entries.length; i++)
                if ((entries[i] = scratch.entry(this, i, payload, payloadStart, lease)) == null) return null;
            return new EntityMetadata(id, List.of(entries));
        } finally {
            if (scratch.kinds.length > Scratch.HELD) this.scratch = null;
            else scratch.clear();
        }
    }

    /** As {@link #read(ByteBuf, FrameLease)}, for a reader that decodes a single packet. */
    EntityMetadata readOnce(ByteBuf input, FrameLease lease) {
        int id = Wire.readVarInt(input);
        int payloadStart = input.readerIndex();
        var decoded = decoder.metadata(version, input, registries, ModelNbtFactory.INSTANCE);
        int payloadLength = decoded.isEmpty()
                ? 0
                : decoded.getLast().offset() + decoded.getLast().length() - payloadStart;
        byte[] payload = lease == null ? ByteBufUtil.getBytes(input, payloadStart, payloadLength) : null;
        var entries = new EntityMetadata.Entry[decoded.size()];
        for (int i = 0; i < entries.length; i++) {
            var value = decoded.get(i);
            entries[i] = entry(
                    value.index(),
                    value.kind(),
                    value.value(),
                    value.offset(),
                    value.length(),
                    payload,
                    payloadStart,
                    lease);
            if (entries[i] == null) return null;
        }
        return new EntityMetadata(id, List.of(entries));
    }

    /** Null when {@code value} is a painting variant this connection cannot resolve. */
    @SuppressWarnings("unchecked") // The metadata call uses the same NBT factory as item conversion.
    private EntityMetadata.Entry entry(
            int index,
            String kind,
            Object value,
            int offset,
            int length,
            byte[] payload,
            int payloadStart,
            FrameLease lease) {
        Object converted = switch (kind) {
            case "item" -> items.item((WireValueDecoder.DecodedItem<NbtValue>) value);
            case "painting_variant" ->
                value instanceof int[] dimensions
                        ? new PaintingSize(dimensions[0], dimensions[1])
                        : paintings.apply((String) value);
            case "pose" -> EntityPose.byId((int) value);
            case "direction" -> Direction.from3DDataValue((int) value);
            case "optional_position" -> value == null ? Optional.empty() : Optional.of(position((int[]) value));
            case "optional_int" -> value == null ? OptionalInt.empty() : OptionalInt.of((int) value);
            default -> value;
        };
        if (converted == null && kind.equals("painting_variant")) return null;
        return lease == null
                ? EntityMetadata.Entry.of(index, converted, payload, offset - payloadStart, length)
                : EntityMetadata.Entry.of(index, converted, lease, offset, length);
    }

    /** One packet's decoded values; held between packets while its capacity stays within {@link #HELD}. */
    private static final class Scratch implements WireValueDecoder.MetadataSink {
        private static final int HELD = 64;

        private int[] indices = new int[4], offsets = new int[4], lengths = new int[4];
        private String[] kinds = new String[4];
        private Object[] values = new Object[4];
        private int size;

        @Override
        public void accept(int index, String kind, Object value, int offset, int length) {
            if (size == kinds.length) {
                int capacity = size * 2;
                indices = Arrays.copyOf(indices, capacity);
                kinds = Arrays.copyOf(kinds, capacity);
                values = Arrays.copyOf(values, capacity);
                offsets = Arrays.copyOf(offsets, capacity);
                lengths = Arrays.copyOf(lengths, capacity);
            }
            indices[size] = index;
            kinds[size] = kind;
            values[size] = value;
            offsets[size] = offset;
            lengths[size] = length;
            size++;
        }

        private EntityMetadata.Entry entry(
                ModelEntityMetadataValues owner, int i, byte[] payload, int payloadStart, FrameLease lease) {
            return owner.entry(indices[i], kinds[i], values[i], offsets[i], lengths[i], payload, payloadStart, lease);
        }

        private void clear() {
            Arrays.fill(values, 0, size, null);
            size = 0;
        }
    }

    private static BlockPos position(int[] values) {
        return new BlockPos(values[0], values[1], values[2]);
    }
}
