package ac.cult.cultac.protocol.packet.clientbound;

import java.util.Objects;

/** Effect identity and strength consumed by entity compensation. */
public record ClientboundUpdateMobEffect(int entityId, String effect, int amplifier) implements ClientboundPacket {
    public ClientboundUpdateMobEffect {
        Objects.requireNonNull(effect);
    }
}
