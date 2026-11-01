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
    @SuppressWarnings("unchecked") // The metadata call uses the same NBT factory as item conversion.
    EntityMetadata read(ByteBuf input, FrameLease lease) {
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
            Object converted = switch (value.kind()) {
                case "item" -> items.item((WireValueDecoder.DecodedItem<NbtValue>) value.value());
                case "painting_variant" ->
                    value.value() instanceof int[] size
                            ? new PaintingSize(size[0], size[1])
                            : paintings.apply((String) value.value());
                case "pose" -> EntityPose.byId((int) value.value());
                case "direction" -> Direction.from3DDataValue((int) value.value());
                case "optional_position" ->
                    value.value() == null ? Optional.empty() : Optional.of(position((int[]) value.value()));
                case "optional_int" ->
                    value.value() == null ? OptionalInt.empty() : OptionalInt.of((int) value.value());
                default -> value.value();
            };
            if (converted == null && value.kind().equals("painting_variant")) return null;
            entries[i] = lease == null
                    ? EntityMetadata.Entry.of(
                            value.index(), converted, payload, value.offset() - payloadStart, value.length())
                    : EntityMetadata.Entry.of(value.index(), converted, lease, value.offset(), value.length());
        }
        return new EntityMetadata(id, List.of(entries));
    }

    private static BlockPos position(int[] values) {
        return new BlockPos(values[0], values[1], values[2]);
    }
}
