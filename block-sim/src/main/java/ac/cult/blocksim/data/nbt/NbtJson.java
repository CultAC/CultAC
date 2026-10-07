package ac.cult.blocksim.data.nbt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Convenience codec fields for behavior readers; typed NBT remains the predicate authority. */
public final class NbtJson {
    private NbtJson() { }
    /** Codec.BOOL accepts NBT numbers: NbtOps#getBooleanValue tests doubleValue != 0. */
    public static boolean booleanValue(JsonElement value) {
        var primitive = value.getAsJsonPrimitive();
        return primitive.isNumber() ? primitive.getAsDouble() != 0.0 : primitive.getAsBoolean();
    }
    /** Literal compound JSON accepted by the raw-NBT codec; component schemas use their own types. */
    public static NbtValue literal(JsonElement value) {
        if (value.isJsonObject()) {
            var fields = new java.util.HashMap<String, NbtValue>();
            value.getAsJsonObject().entrySet().forEach(entry -> fields.put(entry.getKey(), literal(entry.getValue())));
            return new NbtValue.Compound(fields);
        }
        if (value.isJsonArray()) return new NbtValue.Sequence(value.getAsJsonArray().asList().stream().map(NbtJson::literal).toList());
        var primitive = value.getAsJsonPrimitive();
        if (primitive.isString()) return new NbtValue.Text(primitive.getAsString());
        if (primitive.isBoolean()) return new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte)(primitive.getAsBoolean() ? 1 : 0));
        var number = primitive.getAsBigDecimal();
        try {
            var integer = number.toBigIntegerExact();
            int bits = integer.bitLength();
            if (bits <= 7) return new NbtValue.Numeric(NbtValue.Kind.BYTE, integer.byteValue());
            if (bits <= 15) return new NbtValue.Numeric(NbtValue.Kind.SHORT, integer.shortValue());
            if (bits <= 31) return new NbtValue.Numeric(NbtValue.Kind.INT, integer.intValue());
            if (bits <= 63) return new NbtValue.Numeric(NbtValue.Kind.LONG, integer.longValue());
        } catch (ArithmeticException fractional) {
            // Fractions retain the narrowest binary representation that exactly preserves the double.
        }
        double decimal = number.doubleValue();
        return decimal == (double)(float)decimal ? new NbtValue.Numeric(NbtValue.Kind.FLOAT, (float)decimal)
                : new NbtValue.Numeric(NbtValue.Kind.DOUBLE, decimal);
    }
    public static JsonElement encode(NbtValue value) {
        if (value instanceof NbtValue.Numeric numeric) return new JsonPrimitive(numeric.value());
        if (value instanceof NbtValue.Text text) return new JsonPrimitive(text.value());
        if (value instanceof NbtValue.Compound compound) {
            var object = new JsonObject(); compound.values().forEach((key, field) -> object.add(key, encode(field))); return object;
        }
        var array = new JsonArray();
        if (value instanceof NbtValue.Sequence sequence) sequence.values().forEach(field -> array.add(encode(field)));
        else ((NbtValue.PrimitiveArray)value).values().forEach(array::add);
        return array;
    }
}
