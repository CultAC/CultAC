package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.platform.api.world.PlatformChunk;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.platform.velocity.bridge.BridgeSession;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import com.velocitypowered.api.proxy.ServerConnection;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Read-only compensated client view, scoped to one server connection and dimension. */
final class VelocityWorld implements PlatformWorld {
    private final VelocityPlayer player;
    private final ServerConnection server;
    private final String dimension;
    private volatile @Nullable UUID mirroredUid;

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
        var session = bridgedWorld();
        String bridged = session == null ? null : session.worldName();
        if (bridged != null) return bridged;
        return dimension == null ? "unknown" : dimension;
    }

    @Override
    public UUID getUID() {
        // Unbridged, the protocol sends a dimension key, not the server's world UUID.
        var session = bridgedWorld();
        return session == null ? null : session.worldUid();
    }

    /** Records the companion's UID for this world, so a location saved here can be reached later. */
    void mirror(UUID uid) {
        mirroredUid = uid;
    }

    /**
     * The backend world UID for a teleport by a player now on {@code current}: the live mirror, else the last one seen
     * for this dimension; null when unknown or when this world belongs to another backend server.
     */
    @Nullable
    UUID uidOn(@Nullable ServerConnection current) {
        if (current == null || server == null) return null;
        if (server != current
                && !server.getServerInfo()
                        .getName()
                        .equals(current.getServerInfo().getName())) {
            return null;
        }
        UUID live = getUID();
        return live != null ? live : mirroredUid;
    }

    /** The companion's world mirror, when this world is the player's current world. */
    private @Nullable BridgeSession bridgedWorld() {
        var session = player.bridge(ServerCapability.WORLD_INFO);
        var state = player.observed();
        return session != null
                        && state != null
                        && matches(player.getNative().getCurrentServer().orElse(null), state.world)
                ? session
                : null;
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
