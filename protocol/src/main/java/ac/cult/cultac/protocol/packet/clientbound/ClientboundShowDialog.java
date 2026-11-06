package ac.cult.cultac.protocol.packet.clientbound;

import ac.cult.cultac.protocol.value.ByteArray;
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
        // The inline marker is VarInt 0, a single zero byte.
        byte[] bytes = new byte[nbt.length + 1];
        System.arraycopy(nbt, 0, bytes, 1, nbt.length);
        return new ClientboundShowDialog(new ByteArray(bytes));
    }
}
