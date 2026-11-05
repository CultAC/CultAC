package ac.cult.cultac.protocol.codec.movement;

import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WritablePacketCodec;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;

/** 1.21.4 appended the onGround byte. */
public final class ServerboundMoveVehicleCodec implements WritablePacketCodec<ServerboundMoveVehicle> {
    @Override
    public ServerboundMoveVehicle read(ByteBuf input, ProtocolContext context) {
        var position = Wire.readVec3(input);
        float yaw = input.readFloat(), pitch = input.readFloat();
        boolean hasGround = context.version().atLeast(ProtocolVersion.V1_21_4);
        return new ServerboundMoveVehicle(position, yaw, pitch, hasGround && input.readBoolean(), hasGround);
    }

    @Override
    public void write(ByteBuf output, ProtocolContext context, ServerboundMoveVehicle packet) {
        Wire.writeVec3(output, packet.position());
        output.writeFloat(packet.yaw()).writeFloat(packet.pitch());
        if (context.version().atLeast(ProtocolVersion.V1_21_4)) output.writeBoolean(packet.onGround());
    }
}
