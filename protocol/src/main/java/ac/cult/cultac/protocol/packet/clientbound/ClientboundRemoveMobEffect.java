package ac.cult.cultac.protocol.packet.clientbound;

import java.util.Objects;

public record ClientboundRemoveMobEffect(int entityId, String effect) implements ClientboundPacket {
    public ClientboundRemoveMobEffect {
        Objects.requireNonNull(effect);
    }
}
