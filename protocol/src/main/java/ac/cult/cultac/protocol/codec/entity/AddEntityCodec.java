/*
 * Read layout adapted from PacketEvents WrapperPlayServerSpawnEntity,
 * revision 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2022 retrooper and contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.protocol.codec.entity;

import ac.cult.cultac.protocol.PacketCodec;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;

public final class AddEntityCodec implements PacketCodec<ClientboundAddEntity> {
    // The data field of a falling-block entity is a native block-state ID.
    @Override
    public boolean requiresModelValues() {
        return true;
    }

    @Override
    public ClientboundAddEntity read(ByteBuf input, ProtocolContext context) {
        int entityId = Wire.readVarInt(input);
        var uuid = Wire.readUuid(input);
        int entityTypeId = Wire.readVarInt(input);
        var types = context.data().registry("minecraft:entity_type");
        // Vanilla's DefaultedMappedRegistry maps unknown entity IDs to pig.
        String entityType =
                entityTypeId < 0 || entityTypeId >= types.size() ? "minecraft:pig" : types.name(entityTypeId);
        var position = new Vec3d(input.readDouble(), input.readDouble(), input.readDouble());
        // V1_21_9 moved velocity before the angles and switched it to LpVec3.
        boolean modernVelocity = context.version().atLeast(ProtocolVersion.V1_21_9);
        if (modernVelocity) Wire.readLpVec3(input);
        float pitch = Wire.readAngle(input);
        float yaw = Wire.readAngle(input);
        input.readByte(); // Head rotation is not consumed.
        int data = Wire.readVarInt(input);
        if (!modernVelocity) Wire.readShortVelocity(input);
        return new ClientboundAddEntity(entityId, entityType, position, yaw, pitch, data, uuid);
    }
}
