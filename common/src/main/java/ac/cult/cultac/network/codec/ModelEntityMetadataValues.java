package ac.cult.cultac.network.codec;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.entity.PaintingSize;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

/** Consumed metadata uses owned values; every entry retains its original wire bytes. */
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

    EntityMetadata read(ByteBuf input) {
        int id = Wire.readVarInt(input);
        var entries = new ArrayList<EntityMetadata.Entry>();
        for (var value : decoder.metadata(version, input, registries)) {
            Object converted = switch (value.kind()) {
                case "item" -> items.item((WireValueDecoder.ItemValue) value.value());
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
            entries.add(new EntityMetadata.Entry(value.index(), converted, ByteBuffer.wrap(value.bytes())));
        }
        return new EntityMetadata(id, entries);
    }

    private static BlockPos position(int[] values) {
        return new BlockPos(values[0], values[1], values[2]);
    }
}
