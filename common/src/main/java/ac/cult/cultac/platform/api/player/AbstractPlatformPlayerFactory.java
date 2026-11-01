package ac.cult.cultac.platform.api.player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class AbstractPlatformPlayerFactory<T> implements PlatformPlayerFactory {
    protected final PlatformPlayerCache cache = PlatformPlayerCache.getInstance();

    @Override
    public final @Nullable PlatformPlayer getFromUUID(@NotNull UUID uuid) {
        T nativePlayer = getNativePlayer(uuid);
        return nativePlayer == null ? null : lookup(nativePlayer);
    }

    @Override
    public final @Nullable PlatformPlayer getFromName(@NotNull String name) {
        T nativePlayer = getNativePlayer(name);
        return nativePlayer == null ? null : lookup(nativePlayer);
    }

    @SuppressWarnings("unchecked")
    @Override
    public final PlatformPlayer getFromNativePlayerType(@NotNull Object playerObject) {
        return lookup((T) Objects.requireNonNull(playerObject, "playerObject"));
    }

    @Override
    public final PlatformPlayer attach(@NotNull PlatformPlayer player) {
        return cache.addOrGetPlayer(player.getUniqueId(), player);
    }

    /** The attached wrapper when it wraps this native, otherwise a wrapper the cache never holds. */
    private PlatformPlayer lookup(T nativePlayer) {
        PlatformPlayer cachedPlayer = cache.getPlayer(getPlayerUUID(nativePlayer));
        return cachedPlayer != null && cachedPlayer.getNative() == nativePlayer
                ? cachedPlayer
                : createPlatformPlayer(nativePlayer);
    }

    @Override
    public final void invalidatePlayer(@NotNull UUID uuid) {
        cache.removePlayer(uuid);
    }

    @Override
    public final void invalidatePlayer(@NotNull PlatformPlayer player) {
        cache.removePlayer(player);
    }

    @Override
    public final Collection<PlatformPlayer> getOnlinePlayers() {
        Collection<T> nativePlayers = getNativeOnlinePlayers();

        List<PlatformPlayer> platformPlayers = new ArrayList<>(nativePlayers.size());

        for (T nativePlayer : nativePlayers) {
            platformPlayers.add(lookup(nativePlayer));
        }

        return platformPlayers;
    }

    /**
     * Retrieves the native player object for the given UUID.
     *
     * @param uuid the UUID of the player
     * @return the native player object, or null if not found
     */
    protected abstract T getNativePlayer(@NotNull UUID uuid);

    protected abstract T getNativePlayer(@NotNull String name);

    /**
     * Creates a PlatformPlayer instance from the native player object.
     *
     * @param nativePlayer the native player object
     * @return a new PlatformPlayer instance
     */
    protected abstract PlatformPlayer createPlatformPlayer(@NotNull T nativePlayer);

    /**
     * Gets the UUID of the native player.
     *
     * @param nativePlayer the native player object
     * @return the UUID of the player
     */
    protected abstract UUID getPlayerUUID(@NotNull T nativePlayer);

    /**
     * Gets the native online player objects (e.g., Player for Bukkit).
     *
     * @return a collection of native player objects
     */
    protected abstract Collection<T> getNativeOnlinePlayers();
}
