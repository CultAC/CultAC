/*
 * Framing adapted from PacketEvents WrapperConfigServerRegistryData,
 * revision 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2024 retrooper and contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.protocol.wire;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.WireValueDecoder.RegistryEntry;
import ac.cult.cultac.protocol.WireValueDecoder.RegistryValues;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;

/** Modern configuration registry entries. Opaque NBT retains its original bytes and numeric kinds. */
public final class RegistryValueCodec {
    private RegistryValueCodec() {}

    public static RegistryValues read(ByteBuf input) {
        String registry = Wire.readIdentifier(input);
        int count = Wire.readLength(input, input.readableBytes());
        var entries = new ArrayList<RegistryEntry>(count);
        for (int i = 0; i < count; i++) {
            String name = Wire.readIdentifier(input);
            byte[] data = null;
            if (input.readBoolean()) {
                int start = input.readerIndex();
                if (input.getUnsignedByte(start) == 0)
                    throw new MalformedPacketException("Expected non-null registry NBT");
                NbtSkipper.skip(input, 512);
                data = new byte[input.readerIndex() - start];
                input.getBytes(start, data);
            }
            entries.add(new RegistryEntry(name, data));
        }
        return new RegistryValues(registry, entries);
    }
}
