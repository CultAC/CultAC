package ac.cult.cultac.protocol.codec.connection;

import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.WritablePacketCodec;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundShowDialog;
import ac.cult.cultac.protocol.value.ByteArray;
import io.netty.buffer.ByteBuf;

/** The dialog is the complete body; no consumer needs its contents. */
public final class ShowDialogCodec implements WritablePacketCodec<ClientboundShowDialog> {
    @Override
    public ClientboundShowDialog read(ByteBuf input, ProtocolContext context) {
        byte[] body = new byte[input.readableBytes()];
        input.readBytes(body);
        return new ClientboundShowDialog(new ByteArray(body));
    }

    @Override
    public void write(ByteBuf output, ProtocolContext context, ClientboundShowDialog packet) {
        output.writeBytes(packet.body().bytes());
    }
}
