package ac.cult.cultac.protocol.packet.clientbound;

import java.util.List;
import java.util.UUID;

public record ClientboundPlayerInfoRemove(List<UUID> profiles) implements ClientboundPacket {
    public ClientboundPlayerInfoRemove {
        profiles = List.copyOf(profiles);
    }
}
