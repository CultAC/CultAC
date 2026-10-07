/*
 * Layout adapted from PacketEvents WrapperPlayServerPlayerInfoRemove,
 * revision 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2022 retrooper and contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.protocol.codec.entity;

import ac.cult.cultac.protocol.PacketCodec;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoRemove;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;

public final class PlayerInfoRemoveCodec implements PacketCodec<ClientboundPlayerInfoRemove> {
    @Override
    public ClientboundPlayerInfoRemove read(ByteBuf input, ProtocolContext context) {
        int count = Wire.readLength(input, input.readableBytes() / 16);
        var profiles = new java.util.ArrayList<java.util.UUID>(count);
        for (int i = 0; i < count; i++) profiles.add(Wire.readUuid(input));
        return new ClientboundPlayerInfoRemove(profiles);
    }
}
