package ac.cult.cultac.platform.bukkit;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.DormancyController;
import ac.cult.cultac.network.DormantWatch;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.routing.NetworkModule;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.reload.api.HostContext;
import io.netty.channel.Channel;
import io.netty.channel.local.LocalChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Dormant mode for the players on this server, through {@link DormancyController}; the setting for this server decides.
 * A dormant Java player's connection keeps a draining watch until the client answered the pings CultAC wrote, then
 * holds nothing of CultAC and no engine lease; a dormant Bedrock player keeps CultAC's handlers without a CultAC player.
 * Either is checked again when they join again. A Java connection that opens while the server is dormant gets nothing
 * and holds no engine lease; a Geyser connection gets CultAC's handlers without a CultAC player.
 */
final class PaperDormancy implements DormancyController.Platform {
    private static final String DRAINED_KEY = "cultac.dormant.drained";

    /**
     * The players whose watch drained, by UUID, to their connection id as text, empty for none, until they quit or
     * their connection closes; one map for every generation of this server, as a drained connection holds no
     * generation.
     */
    private final Map<UUID, String> drained;

    @SuppressWarnings("unchecked")
    PaperDormancy() {
        HostContext host = RuntimeEnvironment.host();
        drained = host == null
                ? new ConcurrentHashMap<>()
                : (Map<UUID, String>) host.shared(DRAINED_KEY, ConcurrentHashMap::new);
    }

    /** Whether the players on this server are dormant. Any thread. */
    boolean dormant() {
        return DormancyController.dormant(server());
    }

    /** Whether a connection opening dormant gets nothing; Geyser's local connections keep CultAC's handlers. */
    static boolean skips(Channel channel) {
        return !(channel instanceof LocalChannel);
    }

    /** {@code player} quit: releases what their drained connection held, whichever generation it drained in. */
    void quit(Player player) {
        String connectionId = drained.remove(player.getUniqueId());
        if (connectionId != null) release(player.getUniqueId(), connectionId, player);
    }

    /** Removes every watch of this generation. */
    CompletableFuture<Void> remove() {
        List<CompletableFuture<Void>> removed = new ArrayList<>();
        for (DormantWatch watch : List.copyOf(DormantWatch.all())) {
            try {
                removed.add(
                        CompletableFuture.runAsync(watch::leave, watch.channel().eventLoop()));
            } catch (RuntimeException closed) {
                // A stopped loop has no pipeline to clean.
            }
        }
        return CompletableFuture.allOf(removed.toArray(CompletableFuture[]::new));
    }

    private static String server() {
        return NetworkModule.routing().instanceId();
    }

    @Override
    public @Nullable UUID player(CultConnection connection) {
        User user = connection.user();
        return user == null ? null : user.getUUID();
    }

    /** A Bedrock connection keeps CultAC's handlers and its Geyser session tap until it closes. */
    @Override
    public boolean swaps(CultConnection connection) {
        return connection.bedrockBridge() == null;
    }

    @Override
    public String backend(UUID player) {
        return server();
    }

    @Override
    public String target(UUID player) {
        return server();
    }

    @Override
    public String decoder() {
        return CultDecoder.NAME;
    }

    @Override
    public DormantWatch watch(CultConnection connection, boolean play) {
        User user = connection.user();
        PlatformConnection.PlayerBinding binding = connection.platform().playerBinding();
        PlatformPlayer platformPlayer = binding == null ? user.getPlayer() : binding.player();
        return new DormantWatch(
                connection.channel(),
                connection.getObservedProtocol(),
                user.getUUID(),
                platformPlayer == null ? null : platformPlayer.getNative(),
                platformPlayer,
                connection.dormant(),
                new int[0],
                play,
                DormancyController.hooks(),
                true);
    }

    @Override
    public void removeHandlers(CultConnection connection) {
        CultDecoder.uninstall(connection.channel());
        CultEncoder.uninstall(connection.channel());
    }

    @Override
    public void detached(UUID player) {}

    @Override
    public boolean accepting() {
        return false;
    }

    @Override
    public void resume(DormantWatch watch) {
        throw new UnsupportedOperationException("A dormant player on a server is checked again when they join again");
    }

    @Override
    public void closed(DormantWatch watch) {
        CultAPI.INSTANCE.getPlayerDataManager().dormantQuit(watch.player(), connectionId(watch), platformPlayer(watch));
    }

    /**
     * Keeps what the drained connection holds until its player quits or, while this generation is current, the
     * connection closes, which a player who never joined does without a quit; a connection that ended is released at
     * once.
     */
    @Override
    public void drained(DormantWatch watch) {
        UUID player = watch.player();
        String connectionId = watch.state().get("connection-id") instanceof String id ? id : "";
        Object nativePlayer = watch.nativePlayer();
        drained.put(player, connectionId);
        watch.channel().closeFuture().addListener(closed -> {
            if (current() && drained.remove(player, connectionId)) release(player, connectionId, nativePlayer);
        });
        if ((!watch.channel().isActive() || quitting(watch.channel())) && drained.remove(player, connectionId))
            release(player, connectionId, nativePlayer);
    }

    /**
     * The players online that are dormant here, besides this generation's watches and dormant connections: while the
     * server is dormant, every one this generation does not inspect; else those whose connection holds no CultAC
     * handler.
     */
    @Override
    public Collection<UUID> unwatched() {
        boolean dormant = dormant();
        List<UUID> players = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID player = online.getUniqueId();
            if (dormant ? CultAPI.INSTANCE.getPlayerDataManager().getPlayer(player) == null : unhandled(online))
                players.add(player);
        }
        return players;
    }

    /** Releases what {@code player}'s drained connection {@code connectionId} held, as a disconnect does. */
    private static void release(UUID player, String connectionId, @Nullable Object nativePlayer) {
        CultAPI.INSTANCE
                .getPlayerDataManager()
                .dormantQuit(
                        player,
                        connectionId.isEmpty() ? null : UUID.fromString(connectionId),
                        nativePlayer == null
                                ? null
                                : CultAPI.INSTANCE.getPlatformPlayerFactory().getFromNativePlayerType(nativePlayer));
    }

    /** Whether this generation answers for the server. */
    private static boolean current() {
        HostContext host = RuntimeEnvironment.host();
        return host == null || host.current();
    }

    /** Whether the player of {@code channel} left the server, whose quit may have run already. */
    private static boolean quitting(Channel channel) {
        return channel.pipeline().get("packet_handler") instanceof Connection connection
                && connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener
                && listener.player != null
                && listener.player.hasDisconnected();
    }

    /** Whether {@code player}'s connection holds no handler of any CultAC generation. */
    private static boolean unhandled(Player player) {
        Connection connection = BukkitConnectionAdapter.connection(player);
        Channel channel = connection == null ? null : BukkitConnectionAdapter.channel(connection);
        if (channel == null) return false;
        for (String name : channel.pipeline().names()) {
            if (name.startsWith("cult-")) return false;
        }
        return true;
    }

    private static @Nullable UUID connectionId(DormantWatch watch) {
        return watch.state().get("connection-id") instanceof String id ? UUID.fromString(id) : null;
    }

    private static @Nullable PlatformPlayer platformPlayer(DormantWatch watch) {
        return watch.platformPlayer() instanceof PlatformPlayer known ? known : null;
    }
}
