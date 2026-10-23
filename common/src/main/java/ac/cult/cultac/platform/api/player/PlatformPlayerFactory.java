package ac.cult.cultac.platform.api.player;

import java.util.Collection;
import java.util.UUID;

/**
 * Only the runtime that owns a connection caches its player: it {@link #attach attaches} the wrapper when it binds
 * the connection at login and invalidates it when that connection is released. Every lookup returns the attached
 * wrapper when it wraps the same native and otherwise an uncached one, so a runtime never retains a player whose
 * disconnect only another runtime receives.
 */
public interface PlatformPlayerFactory {
    PlatformPlayer getFromName(String name);

    PlatformPlayer getFromUUID(UUID uuid);

    PlatformPlayer getFromNativePlayerType(Object playerObject);

    /**
     * Caches the wrapper bound to a connection this runtime owns and returns the cached instance. Only
     * {@code PacketConnections#completeLogin} calls it; {@code PlayerDataManager} invalidates it on release.
     */
    PlatformPlayer attach(PlatformPlayer player);

    void invalidatePlayer(UUID uuid);

    void invalidatePlayer(PlatformPlayer player);

    Collection<PlatformPlayer> getOnlinePlayers();
}
