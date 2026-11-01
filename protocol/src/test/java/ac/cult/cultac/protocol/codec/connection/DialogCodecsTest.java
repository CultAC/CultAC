package ac.cult.cultac.protocol.codec.connection;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundShowDialog;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomClickAction;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class DialogCodecsTest {
    private static final byte[] NBT = {10, 0};

    @Test
    void anInlineDialogIsZeroThenItsNbt() {
        ByteBuf out = Unpooled.buffer();
        new ShowDialogCodec().write(out, null, ClientboundShowDialog.inline(NBT));
        byte[] written = new byte[out.readableBytes()];
        out.readBytes(written);
        assertArrayEquals(new byte[] {0, 10, 0}, written);
    }

    @Test
    void aClickReadsItsIdAndLengthPrefixedPayload() {
        ServerboundCustomClickAction click = new CustomClickActionCodec().read(click("cultac:dialog/3/1", NBT), null);
        assertEquals("cultac:dialog/3/1", click.id());
        assertArrayEquals(NBT, click.payload().bytes());
    }

    @Test
    void aClickWithoutPayloadCarriesTheEndTag() {
        ServerboundCustomClickAction click = new CustomClickActionCodec().read(click("cultac:x", new byte[] {0}), null);
        assertArrayEquals(new byte[] {0}, click.payload().bytes());
    }

    @Test
    void aPayloadOverTheVanillaLimitOrWithTrailingBytesIsMalformed() {
        ByteBuf large = Unpooled.buffer();
        Wire.writeString(large, "cultac:x", 32767);
        Wire.writeVarInt(large, CustomClickActionCodec.MAX_PAYLOAD + 1);
        assertThrows(MalformedPacketException.class, () -> new CustomClickActionCodec().read(large, null));
        ByteBuf trailing = click("cultac:x", NBT).writeByte(0);
        assertThrows(MalformedPacketException.class, () -> new CustomClickActionCodec().read(trailing, null));
    }

    private static ByteBuf click(String id, byte[] payload) {
        ByteBuf buffer = Unpooled.buffer();
        Wire.writeString(buffer, id, 32767);
        Wire.writeVarInt(buffer, payload.length);
        buffer.writeBytes(payload);
        return buffer;
    }
}
