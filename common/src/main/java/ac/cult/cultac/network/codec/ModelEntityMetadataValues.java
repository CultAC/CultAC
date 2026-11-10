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
import io.netty.util.concurrent.FastThreadLocal;
import java.lang.ref.WeakReference;
import java.util.Arrays;
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

    // Host-owned threads outlive a runtime generation, so their map holds only host-loaded types.
    private static final FastThreadLocal<WeakReference<Scratch>> THREAD_SCRATCH = new FastThreadLocal<>();

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
        var scratch = this.scratch == null ? this.scratch = new Scratch() : this.scratch;
        try {
            return read(input, lease, scratch);
        } finally {
            if (scratch.kinds.length > Scratch.HELD) this.scratch = null;
            else scratch.clear();
        }
    }

    private EntityMetadata read(ByteBuf input, FrameLease lease, Scratch scratch) {
        int id = Wire.readVarInt(input);
        int payloadStart = input.readerIndex();
        decoder.metadata(version, input, registries, ModelNbtFactory.INSTANCE, scratch);
        int size = scratch.size;
        int payloadLength = size == 0 ? 0 : scratch.offsets[size - 1] + scratch.lengths[size - 1] - payloadStart;
        byte[] payload = lease == null ? ByteBufUtil.getBytes(input, payloadStart, payloadLength) : null;
        for (int i = 0; i < size; i++) {
            String kind = scratch.kinds[i];
            Object converted = value(kind, scratch.values[i]);
            if (converted == null && kind.equals("painting_variant")) return null;
            scratch.values[i] = converted;
        }
        return new EntityMetadata(
                id,
                lease == null
                        ? EntityMetadata.entries(
                                size,
                                scratch.indices,
                                scratch.values,
                                scratch.offsets,
                                scratch.lengths,
                                payload,
                                payloadStart)
                        : EntityMetadata.entries(
                                size, scratch.indices, scratch.values, scratch.offsets, scratch.lengths, lease));
    }

    /** As {@link #read(ByteBuf, FrameLease)}, for a reader that decodes a single packet. */
    EntityMetadata readOnce(ByteBuf input, FrameLease lease) {
        WeakReference<Scratch> reference = THREAD_SCRATCH.get();
        Scratch scratch = reference == null ? null : reference.get();
        if (scratch == null) THREAD_SCRATCH.set(new WeakReference<>(scratch = new Scratch()));
        else if (scratch.busy) return read(input, lease, new Scratch());
        scratch.busy = true;
        try {
            return read(input, lease, scratch);
        } finally {
            scratch.busy = false;
            if (scratch.kinds.length > Scratch.HELD) THREAD_SCRATCH.remove();
            else scratch.clear();
        }
    }

    /** The model value of a decoded {@code kind} value; null for a painting variant this connection cannot resolve. */
    @SuppressWarnings("unchecked") // The metadata call uses the same NBT factory as item conversion.
    private Object value(String kind, Object value) {
        return switch (kind) {
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
    }

    /** One packet's decoded values; held between packets while its capacity stays within {@link #HELD}. */
    private static final class Scratch implements WireValueDecoder.MetadataSink {
        private static final int HELD = 64;

        private int[] indices = new int[4], offsets = new int[4], lengths = new int[4];
        private String[] kinds = new String[4];
        private Object[] values = new Object[4];
        private int size;
        /** Whether a single packet read on this scratch's thread is using it. */
        private boolean busy;

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

        private void clear() {
            Arrays.fill(values, 0, size, null);
            size = 0;
        }
    }

    private static BlockPos position(int[] values) {
        return new BlockPos(values[0], values[1], values[2]);
    }
}
