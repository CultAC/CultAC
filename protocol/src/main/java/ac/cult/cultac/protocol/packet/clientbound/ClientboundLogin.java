package ac.cult.cultac.protocol.packet.clientbound;

import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import java.util.Objects;

public record ClientboundLogin(int playerId, boolean showDeathScreen, PlayerSpawnInfo spawnInfo, int chunkRadius)
        implements ClientboundPacket {
    public ClientboundLogin(int playerId, boolean showDeathScreen, PlayerSpawnInfo spawnInfo) {
        this(playerId, showDeathScreen, spawnInfo, 3);
    }

    public ClientboundLogin {
        Objects.requireNonNull(spawnInfo);
    }
}
