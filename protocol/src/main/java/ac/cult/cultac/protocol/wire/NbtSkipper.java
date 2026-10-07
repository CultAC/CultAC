/*
 * Sequential traversal adapted from PacketEvents SequentialNBTReader,
 * revision 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2024 retrooper and contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.protocol.wire;

import ac.cult.cultac.protocol.MalformedPacketException;
import io.netty.buffer.ByteBuf;

/** Finds the end of trusted, opaque NBT without building tags or decoding strings. */
public final class NbtSkipper {
    private NbtSkipper() {}

    /** Borrows the input for this call only. Tag zero is the nullable-tag sentinel. */
    public static void skip(ByteBuf input, int maxDepth) {
        if (maxDepth < 0) throw new IllegalArgumentException("Negative NBT depth limit");
        int type = input.readUnsignedByte();
        if (type != 0) payload(input, type, 0, maxDepth);
    }

    /** Skips one named field's payload after its type and name have been read. */
    public static void skipPayload(ByteBuf input, int type, int depth, int maxDepth) {
        payload(input, type, depth, maxDepth);
    }

    private static void payload(ByteBuf input, int type, int depth, int maxDepth) {
        switch (type) {
            case 1 -> input.skipBytes(1);
            case 2 -> input.skipBytes(2);
            case 3, 5 -> input.skipBytes(4);
            case 4, 6 -> input.skipBytes(8);
            case 7 -> array(input, 1);
            case 8 -> string(input);
            case 9 -> {
                enter(depth, maxDepth);
                int elementType = input.readUnsignedByte();
                int count = input.readInt();
                if (count < 0 || elementType == 0 && count > 0) throw new MalformedPacketException("Invalid NBT list");
                // Native NBT also accepts an unknown element type for an empty list.
                for (int i = 0; i < count; i++) payload(input, elementType, depth + 1, maxDepth);
            }
            case 10 -> {
                enter(depth, maxDepth);
                int childType;
                while ((childType = input.readUnsignedByte()) != 0) {
                    string(input);
                    payload(input, childType, depth + 1, maxDepth);
                }
            }
            case 11 -> array(input, 4);
            case 12 -> array(input, 8);
            default -> throw new MalformedPacketException("Invalid NBT tag type " + type);
        }
    }

    private static void array(ByteBuf input, int elementSize) {
        long bytes = (long) input.readInt() * elementSize;
        if (bytes < 0 || bytes > input.readableBytes()) throw new MalformedPacketException("Invalid NBT array length");
        input.skipBytes((int) bytes);
    }

    private static void string(ByteBuf input) {
        input.skipBytes(input.readUnsignedShort());
    }

    private static void enter(int depth, int maximum) {
        if (depth >= maximum) throw new MalformedPacketException("NBT depth exceeds " + maximum);
    }
}
