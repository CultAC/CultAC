package ac.cult.cultac.protocol.codec.connection;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.PacketCodec;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomClickAction;
import ac.cult.cultac.protocol.value.ByteArray;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;

/** Vanilla length-prefixes the payload and refuses one over 65536 bytes. */
public final class CustomClickActionCodec implements PacketCodec<ServerboundCustomClickAction> {
    static final int MAX_PAYLOAD = 65536;

    @Override
    public ServerboundCustomClickAction read(ByteBuf input, ProtocolContext context) {
        String id = Wire.readIdentifier(input);
        int length = Wire.readLength(input, MAX_PAYLOAD);
        if (length != input.readableBytes()) throw new MalformedPacketException("Custom click payload length mismatch");
        byte[] payload = new byte[length];
        input.readBytes(payload);
        return new ServerboundCustomClickAction(id, new ByteArray(payload));
    }
}
