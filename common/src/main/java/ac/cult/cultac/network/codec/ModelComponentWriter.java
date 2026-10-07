package ac.cult.cultac.network.codec;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.NbtJson;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import io.netty.buffer.ByteBuf;

/** Changed components use portable values and the private versioned Via writer. */
final class ModelComponentWriter {
    private ModelComponentWriter() {}

    static void write(
            ByteBuf output,
            String key,
            Components components,
            WireValueDecoder codecs,
            ProtocolVersion version,
            WireValueDecoder.Registries registries) {
        codecs.writeComponent(version, output, key, BinaryNbt.write(value(components, key)), registries);
    }

    static NbtValue value(Components components, String key) {
        var encoding =
                components.hasEncodedNbt(key) ? nbtZeros(components.encodedNbt(key)) : literal(components.get(key));
        var layout = components.layout(key);
        return layout == null ? encoding : signs(encoding, layout.values().get("negative_zero"));
    }

    /** NBT loads normalize zero; record sign details are restored separately below. */
    private static NbtValue nbtZeros(NbtValue value) {
        if (value instanceof NbtValue.Numeric number) {
            if (number.kind() == NbtValue.Kind.FLOAT && number.value().floatValue() == 0F)
                return new NbtValue.Numeric(NbtValue.Kind.FLOAT, 0F);
            if (number.kind() == NbtValue.Kind.DOUBLE && number.value().doubleValue() == 0D)
                return new NbtValue.Numeric(NbtValue.Kind.DOUBLE, 0D);
        }
        if (value instanceof NbtValue.Compound object) {
            var fields = new java.util.LinkedHashMap<String, NbtValue>();
            object.values().forEach((key, field) -> fields.put(key, nbtZeros(field)));
            return new NbtValue.Compound(fields);
        }
        if (value instanceof NbtValue.Sequence array)
            return new NbtValue.Sequence(
                    array.values().stream().map(ModelComponentWriter::nbtZeros).toList());
        return value;
    }

    private static NbtValue literal(com.google.gson.JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            Number number = value.getAsNumber();
            double decimal = number.doubleValue();
            if (!Double.isFinite(decimal) || Double.doubleToRawLongBits(decimal) == Long.MIN_VALUE)
                return new NbtValue.Numeric(
                        number instanceof Float ? NbtValue.Kind.FLOAT : NbtValue.Kind.DOUBLE, number);
        }
        if (value.isJsonObject()) {
            var fields = new java.util.LinkedHashMap<String, NbtValue>();
            value.getAsJsonObject().entrySet().forEach(entry -> fields.put(entry.getKey(), literal(entry.getValue())));
            return new NbtValue.Compound(fields);
        }
        if (value.isJsonArray())
            return new NbtValue.Sequence(value.getAsJsonArray().asList().stream()
                    .map(ModelComponentWriter::literal)
                    .toList());
        return NbtJson.literal(value);
    }

    private static NbtValue signs(NbtValue value, NbtValue signs) {
        if (signs instanceof NbtValue.Numeric kind)
            return kind.value().intValue() == 5
                    ? new NbtValue.Numeric(NbtValue.Kind.FLOAT, -0.0F)
                    : new NbtValue.Numeric(NbtValue.Kind.DOUBLE, -0.0D);
        if (!(signs instanceof NbtValue.Compound fields)) return value;
        if (value instanceof NbtValue.Compound object) {
            var result = new java.util.LinkedHashMap<>(object.values());
            fields.values().forEach((key, sign) -> result.put(key, signs(result.get(key), sign)));
            return new NbtValue.Compound(result);
        }
        var result = new java.util.ArrayList<>(((NbtValue.Sequence) value).values());
        fields.values().forEach((key, sign) -> {
            int index = Integer.parseInt(key);
            result.set(index, signs(result.get(index), sign));
        });
        return new NbtValue.Sequence(result);
    }
}
