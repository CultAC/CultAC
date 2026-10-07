package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.platform.api.world.PlatformChunk;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import com.velocitypowered.api.proxy.ServerConnection;
import java.util.Objects;
import java.util.UUID;

/** Read-only compensated client view, scoped to one server connection and dimension. */
final class VelocityWorld implements PlatformWorld {
    private final VelocityPlayer player;
    private final ServerConnection server;
    private final String dimension;

    VelocityWorld(VelocityPlayer player, ServerConnection server, String dimension) {
        this.player = player;
        this.server = server;
        this.dimension = dimension;
    }

    CultConnection connection() {
        return player.connection();
    }

    boolean matches(ServerConnection currentServer, String currentDimension) {
        return server == currentServer && Objects.equals(dimension, currentDimension);
    }

    @Override
    public String getName() {
        return dimension == null ? "unknown" : dimension;
    }

    @Override
    public UUID getUID() {
        // The protocol sends a dimension key, not the server's world UUID.
        return null;
    }

    @Override
    public boolean isLoaded() {
        var state = player.observed();
        return state != null
                && server != null
                && dimension != null
                && !connection().disconnected()
                && connection().phase(PacketDirection.CLIENTBOUND) == ConnectionPhase.PLAY
                && matches(player.getNative().getCurrentServer().orElse(null), state.world);
    }

    @Override
    public boolean isChunkLoaded(int x, int z) {
        return isLoaded() && player.observed().compensatedWorld.isChunkLoaded(x, z);
    }

    @Override
    public int getBlockAt(int x, int y, int z) {
        if (!isLoaded()) {
            throw new IllegalStateException("Client world is unavailable");
        }
        return player.observed().compensatedWorld.getBlockStateIdAt(x, y, z);
    }

    @Override
    public PlatformChunk getChunkAt(int x, int z) {
        return (localX, y, localZ) -> getBlockAt((x << 4) + localX, y, (z << 4) + localZ);
    }
}
