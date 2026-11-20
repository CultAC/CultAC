/*
 * Layout adapted from PacketEvents WrapperPlayServerJoinGame,
 * revision 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2022 retrooper and contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.protocol.codec.world;

import ac.cult.cultac.protocol.PacketCodec;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;

public final class LoginCodec implements PacketCodec<ClientboundLogin> {
    @Override
    public ClientboundLogin read(ByteBuf input, ProtocolContext context) {
        int playerId = input.readInt();
        input.readBoolean(); // Hardcore
        // Every level is an identifier with at least a one-byte length prefix.
        int levels = Wire.readLength(input, input.readableBytes());
        for (int i = 0; i < levels; i++) Wire.readIdentifier(input);
        Wire.readVarInt(input); // Maximum players
        Wire.readVarInt(input); // View distance
        Wire.readVarInt(input); // Simulation distance
        input.readBoolean(); // Reduced debug information
        boolean showDeathScreen = input.readBoolean();
        input.readBoolean(); // Limited crafting
        return new ClientboundLogin(
                playerId,
                showDeathScreen,
                SpawnInfoCodec.read(input, context.version().atLeast(ProtocolVersion.V26_3)));
    }

    @Override
    public boolean readsEntirePayload() {
        return false;
    }
}
