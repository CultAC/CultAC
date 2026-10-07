/*
 * NBT framing adapted from PacketEvents DefaultNBTSerializer,
 * revision 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2024 retrooper and contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.blocksim.data.nbt;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import static ac.cult.blocksim.data.nbt.NbtValue.Kind.*;

/** Reads and writes unnamed model-version network NBT. */
public final class BinaryNbt {
    private BinaryNbt() {}

    public static NbtValue read(byte[] bytes) {
        return read(bytes, Long.MAX_VALUE);
    }

    /** Optional client accounted-heap quota; trusted registry/component callers remain unlimited. */
    public static NbtValue read(byte[] bytes, long heapQuota) {
        if (heapQuota < 0) throw new IllegalArgumentException("Negative NBT heap quota");
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            var value = payload(input, input.readUnsignedByte(), 0, new ReadBudget(heapQuota));
            if (input.available() != 0) throw new IOException("Trailing NBT bytes");
            return value;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid network NBT", failure);
        }
    }

    /** PacketEvents NBT framing, with model heterogeneous lists represented by wrapped compounds. */
    public static byte[] write(NbtValue value) {
        try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
            output.writeByte(type(value));
            payload(output, value, 0);
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid network NBT", failure);
        }
    }

    private static int type(NbtValue value) {
        if (value instanceof NbtValue.Numeric number) return switch (number.kind()) {
            case BYTE -> 1; case SHORT -> 2; case INT -> 3; case LONG -> 4; case FLOAT -> 5; case DOUBLE -> 6;
            default -> throw new IllegalArgumentException("Invalid numeric NBT kind");
        };
        if (value instanceof NbtValue.PrimitiveArray array) return switch (array.kind()) {
            case BYTE_ARRAY -> 7; case INT_ARRAY -> 11; case LONG_ARRAY -> 12;
            default -> throw new IllegalArgumentException("Invalid NBT array kind");
        };
        if (value instanceof NbtValue.Text) return 8;
        if (value instanceof NbtValue.Sequence) return 9;
        if (value instanceof NbtValue.Compound) return 10;
        throw new IllegalArgumentException("Missing NBT value");
    }

    private static void payload(DataOutputStream output, NbtValue value, int depth) throws IOException {
        if (value instanceof NbtValue.Numeric number) {
            switch (type(value)) {
                case 1 -> output.writeByte(number.value().byteValue());
                case 2 -> output.writeShort(number.value().shortValue());
                case 3 -> output.writeInt(number.value().intValue());
                case 4 -> output.writeLong(number.value().longValue());
                case 5 -> output.writeFloat(number.value().floatValue());
                case 6 -> output.writeDouble(number.value().doubleValue());
                default -> throw new IOException("Invalid numeric NBT kind");
            }
        } else if (value instanceof NbtValue.PrimitiveArray array) {
            output.writeInt(array.values().size());
            for (long entry : array.values()) switch (type(value)) {
                case 7 -> output.writeByte((byte) entry);
                case 11 -> output.writeInt((int) entry);
                case 12 -> output.writeLong(entry);
                default -> throw new IOException("Invalid NBT array kind");
            }
        } else if (value instanceof NbtValue.Text text) output.writeUTF(text.value());
        else if (value instanceof NbtValue.Sequence list) {
            enter(depth);
            int elementType = list.values().isEmpty() ? 0 : type(list.values().getFirst());
            boolean wrap = list.values().stream().anyMatch(entry -> type(entry) != elementType
                    || entry instanceof NbtValue.Compound compound && compound.values().size() == 1 && compound.values().containsKey(""));
            output.writeByte(wrap ? 10 : elementType);
            output.writeInt(list.values().size());
            for (var entry : list.values()) payload(output,
                    wrap ? new NbtValue.Compound(java.util.Map.of("", entry)) : entry, depth + 1);
        } else if (value instanceof NbtValue.Compound compound) {
            enter(depth);
            for (var entry : compound.values().entrySet()) {
                output.writeByte(type(entry.getValue()));
                output.writeUTF(entry.getKey());
                payload(output, entry.getValue(), depth + 1);
            }
            output.writeByte(0);
        } else throw new IOException("Missing NBT value");
    }

    private static NbtValue payload(DataInputStream input, int type, int depth, ReadBudget budget) throws IOException {
        // Client TagType loaders account these fixed costs, followed by variable data.
        // Compound keys count even when repeated; map entries count only on first insertion.
        budget.consume(switch (type) {
            case 1 -> 9; case 2 -> 10; case 3, 5 -> 12; case 4, 6 -> 16;
            case 7, 11, 12 -> 24; case 8, 9 -> 36; case 10 -> 48; default -> 0;
        });
        return switch (type) {
            case 1 -> new NbtValue.Numeric(BYTE, input.readByte());
            case 2 -> new NbtValue.Numeric(SHORT, input.readShort());
            case 3 -> new NbtValue.Numeric(INT, input.readInt());
            case 4 -> new NbtValue.Numeric(LONG, input.readLong());
            case 5 -> {
                float value = input.readFloat();
                // FloatTag.TYPE.load uses valueOf, whose zero singleton normalizes -0.
                yield new NbtValue.Numeric(FLOAT, value == 0F ? 0F : value);
            }
            case 6 -> {
                double value = input.readDouble();
                yield new NbtValue.Numeric(DOUBLE, value == 0D ? 0D : value);
            }
            case 7, 11, 12 -> {
                int size = type == 7 ? 1 : type == 11 ? 4 : 8;
                int count = length(input, size);
                budget.consume((long) size * count);
                var values = new ArrayList<Long>(count);
                for (int i = 0; i < count; i++) values.add(type == 7 ? (long) input.readByte()
                        : type == 11 ? (long) input.readInt() : input.readLong());
                yield new NbtValue.PrimitiveArray(type == 7 ? BYTE_ARRAY : type == 11 ? INT_ARRAY : LONG_ARRAY, values);
            }
            case 8 -> {
                String text = input.readUTF();
                budget.consume(2L * text.length());
                yield new NbtValue.Text(text);
            }
            case 9 -> {
                enter(depth);
                int elementType = input.readUnsignedByte();
                int count = length(input, 1);
                budget.consume(4L * count);
                if (elementType == 0 && count > 0) throw new IOException("Missing NBT list element type");
                var values = new ArrayList<NbtValue>(count);
                for (int i = 0; i < count; i++) {
                    var value = payload(input, elementType, depth + 1, budget);
                    // Model ListTag.TYPE.load calls addAndUnwrap: a compound containing
                    // only the empty-string marker represents one heterogeneous element.
                    // Unwrap once, preserving a wrapped compound which itself has that key.
                    if (value instanceof NbtValue.Compound compound && compound.values().size() == 1
                            && compound.values().containsKey("")) value = compound.values().get("");
                    values.add(value);
                }
                yield new NbtValue.Sequence(values);
            }
            case 10 -> {
                enter(depth);
                var values = new LinkedHashMap<String, NbtValue>();
                int childType;
                while ((childType = input.readUnsignedByte()) != 0) {
                    String name = input.readUTF();
                    budget.consume(28L + 2L * name.length());
                    var value = payload(input, childType, depth + 1, budget);
                    if (values.put(name, value) == null) budget.consume(36);
                }
                yield new NbtValue.Compound(values);
            }
            default -> throw new IOException("Invalid NBT tag type " + type);
        };
    }

    private static int length(DataInputStream input, int elementSize) throws IOException {
        int count = input.readInt();
        if (count < 0 || (long) count * elementSize > input.available()) throw new IOException("Invalid NBT length");
        return count;
    }

    private static void enter(int depth) throws IOException {
        if (depth >= 512) throw new IOException("NBT depth exceeds 512");
    }

    private static final class ReadBudget {
        private long remaining;
        ReadBudget(long remaining) { this.remaining = remaining; }
        void consume(long bytes) throws IOException {
            if (bytes > remaining) throw new IOException("NBT exceeds accounted heap quota");
            remaining -= bytes;
        }
    }
}
