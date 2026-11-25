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
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.util.List;

public final class EntityPositionSyncCodec implements PacketCodec<ClientboundEntityPositionSync> {
    /**
     * Returned by {@link #read} in place of a sync whose path is linear when the context's state is a {@link Sink}
     * that {@link Sink#sinksPositionSync() takes syncs}; the sync's values went to {@link Sink#positionSync} instead.
     */
    public static final ClientboundEntityPositionSync SUNK =
            new ClientboundEntityPositionSync(0, new PositionPath.Linear(Vec3d.ZERO), 0, 0, false);

    /** Codec state that takes a linear sync's values in place of its packet, one sync at a time. */
    public interface Sink {
        /** Whether the next linear sync's values go to {@link #positionSync}. */
        boolean sinksPositionSync();

        /**
         * The values of a sync that {@link EntityPositionSyncCodec#read} returned as {@link #SUNK}: those of the packet
         * it would have returned, with {@code x}, {@code y} and {@code z} the components of its
         * {@link PositionPath.Linear} end position.
         */
        void positionSync(int entityId, double x, double y, double z, float yaw, float pitch, boolean onGround);
    }

    @Override
    public ClientboundEntityPositionSync read(ByteBuf input, ProtocolContext context) {
        int id = Wire.readVarInt(input);
        PositionPath position = null;
        double x, y, z;
        if (context.version().atLeast(ProtocolVersion.V26_3)
                // Native ByIdMap uses LINEAR for every unknown type ID.
                && Wire.readVarInt(input) == 1) {
            position = readSteps(input);
            x = y = z = 0;
        } else {
            Wire.requireBytes(input, 24);
            x = input.readDouble();
            y = input.readDouble();
            z = input.readDouble();
            // Legacy PositionMoveRotation delta: not consumed by this route.
            if (!context.version().atLeast(ProtocolVersion.V26_3)) input.skipBytes(24);
        }
        float yaw = input.readFloat();
        float pitch = input.readFloat();
        boolean onGround = input.readBoolean();
        if (position == null) {
            if (context.state() instanceof Sink sink && sink.sinksPositionSync()) {
                sink.positionSync(id, x, y, z, yaw, pitch, onGround);
                return SUNK;
            }
            position = new PositionPath.Linear(new Vec3d(x, y, z));
        }
        return new ClientboundEntityPositionSync(id, position, yaw, pitch, onGround);
    }

    private static PositionPath readSteps(ByteBuf input) {
        int count = Wire.readVarInt(input);
        if (count <= 0 || count > input.readableBytes() / 25)
            throw new MalformedPacketException("Invalid position step count " + count);
        var steps = new PositionPath.Step[count];
        for (int i = 0; i < count; i++) steps[i] = new PositionPath.Step(Wire.readVec3(input), Wire.readVarInt(input));
        return new PositionPath.Stepped(List.of(steps));
    }
}
