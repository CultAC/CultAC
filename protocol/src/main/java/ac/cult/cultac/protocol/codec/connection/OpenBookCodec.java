package ac.cult.cultac.protocol.codec.connection;

import ac.cult.cultac.protocol.PacketCodec;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenBook;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;

public final class OpenBookCodec implements PacketCodec<ClientboundOpenBook> {
    @Override
    public ClientboundOpenBook read(ByteBuf input, ProtocolContext context) {
        return new ClientboundOpenBook(Wire.readVarInt(input) == 1 ? Hand.OFF_HAND : Hand.MAIN_HAND);
    }
}
