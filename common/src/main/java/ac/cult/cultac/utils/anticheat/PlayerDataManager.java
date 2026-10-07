package ac.cult.cultac.utils.anticheat;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.network.netty.channel.ChannelHelper;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.player.PlatformPlayerCache;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.floodgate.FloodgateUtil;
import ac.cult.cultac.utils.floodgate.GeyserUtil;
import ac.grim.grimac.api.event.events.GrimJoinEvent;
import ac.grim.grimac.api.event.events.GrimQuitEvent;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.jetbrains.annotations.Nullable;

public class PlayerDataManager {
    private static final class Channels {
        private static final GrimJoinEvent.Channel JOIN =
                CultAPI.INSTANCE.getEventBus().get(GrimJoinEvent.class);
        private static final GrimQuitEvent.Channel QUIT =
                CultAPI.INSTANCE.getEventBus().get(GrimQuitEvent.class);
    }

    // Enumeration only; each session owns its sole player attachment.
    private final Set<CultPlayer> activePlayers = ConcurrentHashMap.newKeySet();
    // Exemption belongs to one connection, not to an identity. Two simultaneous
    // connections may legitimately present the same UUID; carrying an exemption
    // across them would let the replacement connection evade the anticheat.
    private final Set<User> exemptUsers = new CopyOnWriteArraySet<>();

    public boolean isExemptUser(@Nullable User user) {
        return user != null && exemptUsers.contains(user);
    }

    public void exemptUser(@Nullable User user) {
        if (user != null) {
            exemptUsers.add(user);
        }
    }

    public boolean clearExemptions(@Nullable User user) {
        return user != null && exemptUsers.remove(user);
    }

    public CultPlayer getPlayer(final PlatformPlayer player) {
        if (player == null) {
            return null;
        }
        User user = CultAPI.INSTANCE.getNetworkManager().getUser(player);
        return getPlayer(user);
    }

    @Nullable
    public CultPlayer getPlayer(final UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return getPlayer(CultAPI.INSTANCE.getNetworkManager().getUser(uuid));
    }

    public boolean shouldCheck(User user) {
        // assume to check until we can prove otherwise
        if (user.getUUID() == null) return true;

        if (isExemptUser(user)) return false;
        if (!ChannelHelper.isOpen(user.getChannel())) return false;

        // Match CultAC: permission state belongs to the exact Cult connection.
        CultPlayer cultPlayer = getPlayer(user);
        if (cultPlayer != null && cultPlayer.hasPermission("cult.exempt")) {
            exemptUser(user);
            return false;
        }

        return true;
    }

    @Nullable
    public CultPlayer getPlayer(final User user) {
        if (user == null) {
            return null;
        }
        return user.getCultPlayer();
    }

    @Nullable
    public User getUser(final PlatformPlayer player) {
        return CultAPI.INSTANCE.getNetworkManager().getUser(player);
    }

    public void addUser(final User user) {
        if (!shouldCheck(user)) {
            remove(user);
            return;
        }
        var session = user.getCultConnection();
        CultPlayer created;
        synchronized (session) {
            if (session.disconnected() || session.player() != null) return;
            created = createPlayer(user);
            session.player(created);
            activePlayers.add(created);
        }

        Channels.JOIN.fire(created);
        UUID uuid = user.getUUID();
        if (uuid != null && CultAPI.INSTANCE.getDataStoreLifecycle() != null) {
            CultAPI.INSTANCE.getDataStoreLifecycle().playerToggleStore().prefetch(uuid);
        }
    }

    public boolean remove(final User user) {
        if (user == null || user.getUUID() == null) {
            return false;
        }

        CultPlayer tracked;
        synchronized (user.getCultConnection()) {
            tracked = user.getCultPlayer();
            user.getCultConnection().player(null);
            if (tracked != null) activePlayers.remove(tracked);
        }
        if (tracked == null) {
            return false;
        }
        tracked.onRemove();
        return true;
    }

    /**
     * Ends only this exact connection. Another User presenting the same UUID has
     * its own session attachment and cannot be removed by this path.
     */
    public boolean onDisconnect(final User user) {
        if (user == null || user.getUUID() == null) {
            clearExemptions(user);
            return false;
        }

        CultPlayer tracked;
        synchronized (user.getCultConnection()) {
            tracked = user.getCultPlayer();
            user.getCultConnection().player(null);
            if (tracked != null) activePlayers.remove(tracked);
        }
        if (tracked == null) {
            clearExemptions(user);
            return false;
        }

        cleanupOwnedSession(tracked);
        return true;
    }

    private void cleanupOwnedSession(CultPlayer tracked) {
        User user = tracked.user;
        UUID uuid = user.getUUID();

        tracked.onRemove();
        clearExemptions(user);
        Channels.QUIT.fire(tracked);
        if (CultAPI.INSTANCE.getDataStoreLifecycle() != null) {
            CultAPI.INSTANCE
                    .getDataStoreLifecycle()
                    .liveWriteHooks()
                    .onQuitFromUserDisconnect(user, tracked, System.currentTimeMillis());
            CultAPI.INSTANCE.getDataStoreLifecycle().playerToggleStore().evict(uuid);
        }

        PlatformPlayer quittingPlayer = user.getPlayer();
        boolean currentPlayer =
                quittingPlayer != null && PlatformPlayerCache.getInstance().getPlayer(uuid) == quittingPlayer;
        if (currentPlayer) {
            CultAPI.INSTANCE.getAlertManager().handlePlayerQuit(quittingPlayer);
        }
        if (currentPlayer && CultAPI.INSTANCE.getSpectateManager() != null) {
            CultAPI.INSTANCE.getSpectateManager().onQuit(uuid);
        }
        if (quittingPlayer != null && CultAPI.INSTANCE.getPlatformPlayerFactory() != null) {
            CultAPI.INSTANCE.getPlatformPlayerFactory().invalidatePlayer(quittingPlayer);
        }
    }

    public Collection<CultPlayer> getEntries() {
        return java.util.Collections.unmodifiableSet(activePlayers);
    }

    public int size() {
        return activePlayers.size();
    }

    private CultPlayer createPlayer(User user) {
        BedrockPlayerState bedrockState = initialBedrockState(user);
        if (bedrockState != null) {
            return new CultPlayer(user, MovementPlatform.BEDROCK, bedrockState);
        }
        return new CultPlayer(user);
    }

    private BedrockPlayerState initialBedrockState(User user) {
        // A bridge matched to this socket is authoritative even before Geyser registers its UUID.
        if (user.getCultConnection().bedrockBridge() != null || isKnownBedrockPlayer(user.getUUID())) {
            return createBedrockState(user.getUUID());
        }
        return null;
    }

    private BedrockPlayerState createBedrockState(UUID uuid) {
        BedrockPlayerState state = new BedrockPlayerState(uuid);
        if (CultAPI.INSTANCE.getConfigManager() != null) {
            state.setSetbacksEnabled(CultAPI.INSTANCE.getConfigManager().isBedrockMovementSetbacksEnabled());
        }
        return state;
    }

    private boolean isKnownBedrockPlayer(UUID uuid) {
        return FloodgateUtil.isFloodgatePlayer(uuid)
                || isGeyserFormattedUuid(uuid)
                || ((CultAPI.INSTANCE.getPluginManager().isPluginEnabled("Geyser-Spigot")
                                || CultAPI.INSTANCE.getPluginManager().isPluginEnabled("Geyser-Velocity"))
                        && GeyserUtil.isGeyserPlayer(uuid));
    }

    private boolean isGeyserFormattedUuid(UUID uuid) {
        return uuid.toString().startsWith("00000000-0000-0000-0009");
    }
}
