/*
 * Read layout adapted from PacketEvents WrapperPlayServerUpdateAttributes,
 * revision 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2022 retrooper and contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.protocol.codec.entity;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.PacketCodec;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.util.List;

public final class UpdateAttributesCodec implements PacketCodec<ClientboundUpdateAttributes> {
    private static final AttributeSnapshot[] NO_ATTRIBUTES = {};
    private static final AttributeModifier[] NO_MODIFIERS = {};

    @Override
    public ClientboundUpdateAttributes read(ByteBuf input, ProtocolContext context) {
        int entityId = Wire.readVarInt(input);
        // The reviewed 26.2/26.3 codecs limit this list to 128; both 1.21 bundles are unbounded.
        int count = readCount(input, context.version().atLeast(ProtocolVersion.V26_2) ? 128 : Integer.MAX_VALUE);
        var attributes = count == 0 ? NO_ATTRIBUTES : new AttributeSnapshot[count];
        for (int i = 0; i < count; i++) {
            String attribute = context.data().registry("minecraft:attribute").name(Wire.readVarInt(input));
            double base = input.readDouble();
            int modifiersCount = readCount(input, Integer.MAX_VALUE);
            var modifiers = modifiersCount == 0 ? NO_MODIFIERS : new AttributeModifier[modifiersCount];
            for (int j = 0; j < modifiersCount; j++) {
                String id = Wire.readIdentifier(input);
                double amount = input.readDouble();
                // Vanilla idMapper uses a VarInt and ByIdMap.ZERO, including for unknown IDs.
                var operation = switch (Wire.readVarInt(input)) {
                    case 1 -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
                    case 2 -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
                    default -> AttributeModifier.Operation.ADD_VALUE;
                };
                modifiers[j] = new AttributeModifier(id, amount, operation);
            }
            attributes[i] = new AttributeSnapshot(attribute, base, List.of(modifiers));
        }
        return new ClientboundUpdateAttributes(entityId, List.of(attributes));
    }

    private static int readCount(ByteBuf input, int max) {
        int count = Wire.readVarInt(input);
        // Both entries need at least an ID, a double and a count/operation (ten bytes).
        if (count < 0 || count > max || count > input.readableBytes() / 10) {
            throw new MalformedPacketException("Invalid attribute list size " + count);
        }
        return count;
    }
}
