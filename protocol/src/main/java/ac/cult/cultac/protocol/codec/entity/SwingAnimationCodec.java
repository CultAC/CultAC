/*
 * Layout adapted from PacketEvents WrapperPlayServerSwingAnimation,
 * revision 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2022 retrooper and contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.protocol.codec.entity;

import ac.cult.cultac.protocol.PacketCodec;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSwingAnimation;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;

public final class SwingAnimationCodec implements PacketCodec<ClientboundSwingAnimation> {
    @Override
    public ClientboundSwingAnimation read(ByteBuf input, ProtocolContext context) {
        int entityId = Wire.readVarInt(input);
        int hand = Wire.readVarInt(input);
        return new ClientboundSwingAnimation(entityId, hand, Wire.readVarInt(input));
    }

    // The unconsumed suffix remains in the original forwarded packet.
    @Override
    public boolean readsEntirePayload() {
        return false;
    }
}
