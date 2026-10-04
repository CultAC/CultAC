/*
 * 26.3 layout adapted from PacketEvents WrapperPlayServerHeldItemChange,
 * revision 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2022 retrooper and contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.protocol.codec.connection;

import ac.cult.cultac.protocol.PacketCodec;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHeldSlot;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;

public final class SetHeldSlotCodec implements PacketCodec<ClientboundSetHeldSlot> {
    @Override
    public ClientboundSetHeldSlot read(ByteBuf input, ProtocolContext context) {
        // The official 1.21.2/3 packet carries a signed byte; 1.21.4 changed it to VarInt.
        return new ClientboundSetHeldSlot(
                context.version().atLeast(ProtocolVersion.V1_21_4) ? Wire.readVarInt(input) : input.readByte());
    }
}
