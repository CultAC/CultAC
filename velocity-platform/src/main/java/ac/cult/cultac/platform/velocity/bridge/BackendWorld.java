package ac.cult.cultac.platform.velocity.bridge;

import ac.cult.cultac.platform.api.world.PlatformWorld;
import com.velocitypowered.api.proxy.ServerConnection;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** A world the companion on {@code server} reported by UID; a teleport target there, never readable on the proxy. */
public final class BackendWorld implements PlatformWorld {
    private final String server;
    private final UUID uid;
    private final String name;

    public BackendWorld(String server, UUID uid, String name) {
        this.server = Objects.requireNonNull(server);
        this.uid = Objects.requireNonNull(uid);
        this.name = Objects.requireNonNull(name);
    }

    /** The world UID for a teleport by a player now on {@code current}; null on any other backend server. */
    public @Nullable UUID uidOn(@Nullable ServerConnection current) {
        return current != null && server.equals(current.getServerInfo().getName()) ? uid : null;
    }

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public int getBlockAt(int x, int y, int z) {
        throw new IllegalStateException("Backend world " + name + " on " + server + " is not readable on the proxy");
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public UUID getUID() {
        return uid;
    }

    @Override
    public boolean isLoaded() {
        return false;
    }
}
