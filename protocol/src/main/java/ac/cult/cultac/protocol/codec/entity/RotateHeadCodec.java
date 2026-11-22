package ac.cult.cultac.protocol.codec.entity;

import ac.cult.cultac.protocol.PacketCodec;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRotateHead;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;

public final class RotateHeadCodec implements PacketCodec<ClientboundRotateHead> {
    @Override
    public ClientboundRotateHead read(ByteBuf input, ProtocolContext context) {
        return new ClientboundRotateHead(Wire.readVarInt(input), Wire.readAngle(input));
    }
}
