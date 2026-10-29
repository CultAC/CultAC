package ac.cult.cultac.protocol.packet.serverbound;

import ac.cult.cultac.protocol.value.ByteArray;
import java.util.Objects;

/**
 * A click on a custom action, such as a dialog button: its id and its payload as unnamed network NBT, a lone end tag
 * when the click carries none.
 */
public record ServerboundCustomClickAction(String id, ByteArray payload) implements ServerboundPacket {
    public ServerboundCustomClickAction {
        Objects.requireNonNull(id);
        Objects.requireNonNull(payload);
    }
}
