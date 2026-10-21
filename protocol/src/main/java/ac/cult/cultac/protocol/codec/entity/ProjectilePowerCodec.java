package ac.cult.cultac.protocol.codec.entity;

import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.WritablePacketCodec;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundProjectilePower;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;

/** {@code ClientboundProjectilePowerPacket}: a varint entity id and the double acceleration power. */
public final class ProjectilePowerCodec implements WritablePacketCodec<ClientboundProjectilePower> {
    @Override
    public ClientboundProjectilePower read(ByteBuf input, ProtocolContext context) {
        int id = Wire.readVarInt(input);
        return new ClientboundProjectilePower(id, input.readDouble());
    }

    @Override
    public void write(ByteBuf output, ProtocolContext context, ClientboundProjectilePower packet) {
        Wire.writeVarInt(output, packet.entityId());
        output.writeDouble(packet.accelerationPower());
    }
}
