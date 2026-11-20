package ac.cult.cultac.protocol.packet.clientbound;

import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import java.util.Objects;

public record ClientboundLogin(int playerId, boolean showDeathScreen, PlayerSpawnInfo spawnInfo)
        implements ClientboundPacket {
    public ClientboundLogin {
        Objects.requireNonNull(spawnInfo);
    }
}
