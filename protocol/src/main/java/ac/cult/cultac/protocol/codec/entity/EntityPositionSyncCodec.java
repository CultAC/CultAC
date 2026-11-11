/*
 * Reads adapted from PacketEvents WrapperPlayServerEntityPositionSync and positionpath,
 * revision 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2024 retrooper and contributors; 2026 PacketEvents contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.protocol.codec.entity;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.PacketCodec;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.util.List;

public final class EntityPositionSyncCodec implements PacketCodec<ClientboundEntityPositionSync> {
    @Override
    public ClientboundEntityPositionSync read(ByteBuf input, ProtocolContext context) {
        int id = Wire.readVarInt(input);
        PositionPath position;
        if (context.version().atLeast(ProtocolVersion.V26_3)) position = readPath(input);
        else {
            position = new PositionPath.Linear(Wire.readVec3(input));
            input.skipBytes(24); // Legacy PositionMoveRotation delta: not consumed by this route.
        }
        return new ClientboundEntityPositionSync(
                id, position, input.readFloat(), input.readFloat(), input.readBoolean());
    }

    private static PositionPath readPath(ByteBuf input) {
        // Native ByIdMap uses LINEAR for every unknown type ID.
        if (Wire.readVarInt(input) != 1) return new PositionPath.Linear(Wire.readVec3(input));
        int count = Wire.readVarInt(input);
        if (count <= 0 || count > input.readableBytes() / 25)
            throw new MalformedPacketException("Invalid position step count " + count);
        var steps = new PositionPath.Step[count];
        for (int i = 0; i < count; i++) steps[i] = new PositionPath.Step(Wire.readVec3(input), Wire.readVarInt(input));
        return new PositionPath.Stepped(List.of(steps));
    }
}
