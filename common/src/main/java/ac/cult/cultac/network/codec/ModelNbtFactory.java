package ac.cult.cultac.network.codec;

import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.protocol.WireValueDecoder.NbtFactory;
import java.util.List;
import java.util.Map;

/** One stateless builder; the wire codec constructs the existing immutable model tree directly. */
final class ModelNbtFactory implements NbtFactory<NbtValue> {
    static final ModelNbtFactory INSTANCE = new ModelNbtFactory();

    private ModelNbtFactory() {}

    public NbtValue read(byte[] bytes) {
        return BinaryNbt.read(bytes);
    }

    public NbtValue number(int type, Number value) {
        return new NbtValue.Numeric(
                switch (type) {
                    case 1 -> NbtValue.Kind.BYTE;
                    case 2 -> NbtValue.Kind.SHORT;
                    case 3 -> NbtValue.Kind.INT;
                    case 4 -> NbtValue.Kind.LONG;
                    case 5 -> NbtValue.Kind.FLOAT;
                    case 6 -> NbtValue.Kind.DOUBLE;
                    default -> throw new IllegalArgumentException("Invalid numeric NBT kind");
                },
                value);
    }

    public NbtValue text(String value) {
        return new NbtValue.Text(value);
    }

    public NbtValue array(int type, List<Long> values) {
        return new NbtValue.PrimitiveArray(
                switch (type) {
                    case 7 -> NbtValue.Kind.BYTE_ARRAY;
                    case 11 -> NbtValue.Kind.INT_ARRAY;
                    case 12 -> NbtValue.Kind.LONG_ARRAY;
                    default -> throw new IllegalArgumentException("Invalid NBT array kind");
                },
                values);
    }

    public NbtValue sequence(List<NbtValue> values) {
        return new NbtValue.Sequence(values);
    }

    public NbtValue compound(Map<String, NbtValue> values) {
        return NbtValue.Compound.of(values);
    }
}
