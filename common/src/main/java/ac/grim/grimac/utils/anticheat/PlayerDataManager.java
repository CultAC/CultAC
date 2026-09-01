package ac.grim.grimac.utils.anticheat;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.event.events.GrimJoinEvent;
import ac.grim.grimac.api.event.events.GrimQuitEvent;
import ac.grim.grimac.bedrock.MovementPlatform;
import ac.grim.grimac.bedrock.player.BedrockPlayerState;
import ac.grim.grimac.network.netty.channel.ChannelHelper;
import ac.grim.grimac.network.protocol.player.User;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.player.PlatformPlayerCache;
import ac.grim.grimac.utils.floodgate.FloodgateUtil;
import ac.grim.grimac.utils.floodgate.GeyserUtil;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import javax.annotation.Nullable;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class PlayerDataManager {
    private static final class Channels {
        private static final GrimJoinEvent.Channel JOIN =
                GrimAPI.INSTANCE.getEventBus().get(GrimJoinEvent.class);
        private static final GrimQuitEvent.Channel QUIT =
                GrimAPI.INSTANCE.getEventBus().get(GrimQuitEvent.class);
    }

    // CultAC/PacketEvents keyed this by User. A UUID identifies an account, not
    // a connection; two live connections with the same UUID must both be checked.
    private final ConcurrentHashMap<User, GrimPlayer> playerDataMap = new ConcurrentHashMap<>();
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

    public GrimPlayer getPlayer(final Player player) {
        if (player == null) {
            return null;
        }
        // External (proxy-hosted) players have no Grim session on this server.
        if (MultiLibUtil.isExternalPlayer(player)) {
            return null;
        }
        User user = GrimAPI.INSTANCE.getNetworkManager().getUser(player);
        return getPlayer(user);
    }

    @Nullable
    public GrimPlayer getPlayer(final UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return getPlayer(GrimAPI.INSTANCE.getNetworkManager().getUser(uuid));
    }

    public boolean shouldCheck(User user) {
        // assume to check until we can prove otherwise
        if (user.getUUID() == null) return true;

        if (isExemptUser(user)) return false;
        if (!ChannelHelper.isOpen(user.getChannel())) return false;

        // Match CultAC: permission state belongs to the exact Grim connection.
        GrimPlayer grimPlayer = getPlayer(user);
        if (grimPlayer != null && grimPlayer.hasPermission("grim.exempt")) {
            exemptUser(user);
            return false;
        }

        return true;
    }

    @Nullable
    public GrimPlayer getPlayer(final User user) {
        if (user == null) {
            return null;
        }
        return playerDataMap.get(user);
    }

    @Nullable
    public User getUser(final Player player) {
        return GrimAPI.INSTANCE.getNetworkManager().getUser(player);
    }

    public void addUser(final User user) {
        if (!shouldCheck(user)) {
            remove(user);
            return;
        }
        if (playerDataMap.containsKey(user)) {
            return;
        }

        GrimPlayer created = createPlayer(user);
        GrimPlayer existing = playerDataMap.putIfAbsent(user, created);
        if (existing != null) {
            created.onRemove();
            return;
        }

        Channels.JOIN.fire(created);
        UUID uuid = user.getUUID();
        if (uuid != null && GrimAPI.INSTANCE.getDataStoreLifecycle() != null) {
            GrimAPI.INSTANCE.getDataStoreLifecycle().playerToggleStore().prefetch(uuid);
        }
    }

    public boolean remove(final User user) {
        if (user == null || user.getUUID() == null) {
            return false;
        }

        GrimPlayer tracked = playerDataMap.remove(user);
        if (tracked == null) {
            return false;
        }
        tracked.onRemove();
        return true;
    }

    /**
     * Ends only this exact connection. Another User presenting the same UUID has
     * its own map entry and cannot be removed by this path.
     */
    public boolean onDisconnect(final User user) {
        if (user == null || user.getUUID() == null) {
            clearExemptions(user);
            return false;
        }

        GrimPlayer tracked = playerDataMap.remove(user);
        if (tracked == null) {
            clearExemptions(user);
            return false;
        }

        cleanupOwnedSession(tracked);
        return true;
    }

    private void cleanupOwnedSession(GrimPlayer tracked) {
        User user = tracked.user;
        UUID uuid = user.getUUID();

        tracked.onRemove();
        clearExemptions(user);
        Channels.QUIT.fire(tracked);
        if (GrimAPI.INSTANCE.getDataStoreLifecycle() != null) {
            GrimAPI.INSTANCE.getDataStoreLifecycle().liveWriteHooks()
                    .onQuitFromUserDisconnect(user, tracked, System.currentTimeMillis());
            GrimAPI.INSTANCE.getDataStoreLifecycle().playerToggleStore().evict(uuid);
        }

        PlatformPlayer quittingPlayer = PlatformPlayerCache.getInstance().getPlayer(uuid);
        if (quittingPlayer != null) {
            GrimAPI.INSTANCE.getAlertManager().handlePlayerQuit(quittingPlayer);
        }
        if (GrimAPI.INSTANCE.getSpectateManager() != null) {
            GrimAPI.INSTANCE.getSpectateManager().onQuit(uuid);
        }
        if (GrimAPI.INSTANCE.getPlatformPlayerFactory() != null) {
            GrimAPI.INSTANCE.getPlatformPlayerFactory().invalidatePlayer(uuid);
        }
    }

    public Collection<GrimPlayer> getEntries() {
        return playerDataMap.values();
    }

    public int size() {
        return playerDataMap.size();
    }

    private GrimPlayer createPlayer(User user) {
        BedrockPlayerState bedrockState = initialBedrockState(user.getUUID());
        if (bedrockState != null) {
            return new GrimPlayer(user, MovementPlatform.BEDROCK, bedrockState);
        }
        return new GrimPlayer(user);
    }

    private BedrockPlayerState initialBedrockState(UUID uuid) {
        if (isKnownBedrockPlayer(uuid)) {
            return createBedrockState(uuid);
        }
        return null;
    }

    private BedrockPlayerState createBedrockState(UUID uuid) {
        BedrockPlayerState state = new BedrockPlayerState(uuid);
        if (GrimAPI.INSTANCE.getConfigManager() != null) {
            state.setSetbacksEnabled(GrimAPI.INSTANCE.getConfigManager().isBedrockMovementSetbacksEnabled());
        }
        return state;
    }

    private boolean isKnownBedrockPlayer(UUID uuid) {
        return FloodgateUtil.isFloodgatePlayer(uuid)
                || isGeyserFormattedUuid(uuid)
                || (Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot") && GeyserUtil.isGeyserPlayer(uuid));
    }

    private boolean isGeyserFormattedUuid(UUID uuid) {
        return uuid.toString().startsWith("00000000-0000-0000-0009");
    }
}
