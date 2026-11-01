package ac.cult.cultac.protocol.packet.clientbound;

import ac.cult.cultac.protocol.value.ByteArray;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Objects;

/**
 * A dialog to show, as its complete body: a registry reference, or 0 and the dialog inline as unnamed network NBT. No
 * consumer reads a dialog; Cult only writes inline ones.
 */
public record ClientboundShowDialog(ByteArray body) implements ClientboundPacket {
    public ClientboundShowDialog {
        Objects.requireNonNull(body);
    }

    /** The dialog {@code nbt} encodes, sent inline. */
    public static ClientboundShowDialog inline(byte[] nbt) {
        ByteBuf body = Unpooled.buffer(nbt.length + 1);
        try {
            Wire.writeVarInt(body, 0);
            body.writeBytes(nbt);
            byte[] bytes = new byte[body.readableBytes()];
            body.readBytes(bytes);
            return new ClientboundShowDialog(new ByteArray(bytes));
        } finally {
            body.release();
        }
    }
}
