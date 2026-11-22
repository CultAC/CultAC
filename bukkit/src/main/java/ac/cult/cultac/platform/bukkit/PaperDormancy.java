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
import io.netty.channel.Channel;
import io.netty.channel.local.LocalChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.Nullable;

/**
 * Dormant mode for the players on this server, through {@link DormancyController}; the setting for this server decides.
 * A dormant Java player's connection keeps a draining watch until the client answered the pings CultAC wrote, then
 * holds nothing of CultAC and no engine lease; a dormant Bedrock player loses only their CultAC player. Either is
 * checked again when they join again. A Java connection that opens while the server is dormant gets nothing.
 */
final class PaperDormancy implements DormancyController.Platform {
    /** The players whose watch drained, by UUID, until they quit. */
    private final Map<UUID, Drained> drained = new ConcurrentHashMap<>();
    /** The connections that opened while the server was dormant and got nothing. */
    private final Set<Channel> skipped = ConcurrentHashMap.newKeySet();

    /** What a drained player's quit releases. */
    private record Drained(
            @Nullable UUID connectionId, @Nullable PlatformPlayer player) {}

    /** Whether a connection opening now gets nothing, which it records; Geyser's local connections are attached. */
    boolean skips(Channel channel) {
        if (channel instanceof LocalChannel || !DormancyController.dormant(server())) return false;
        skipped.removeIf(open -> !open.isActive());
        skipped.add(channel);
        return true;
    }

    /** {@code player} quit: releases what their drained connection held. */
    void quit(UUID player) {
        Drained left = drained.remove(player);
        if (left != null)
            CultAPI.INSTANCE.getPlayerDataManager().dormantQuit(player, left.connectionId(), left.player());
    }

    /** Removes every watch of this generation. */
    CompletableFuture<Void> remove() {
        List<CompletableFuture<Void>> removed = new ArrayList<>();
        for (DormantWatch watch : List.copyOf(DormantWatch.all())) {
            Channel channel = watch.channel();
            try {
                removed.add(CompletableFuture.runAsync(
                        () -> {
                            if (channel.pipeline().context(watch) != null)
                                channel.pipeline().remove(watch);
                        },
                        channel.eventLoop()));
            } catch (RuntimeException closed) {
                // A loop that no longer runs has no pipeline left to clean.
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

    @Override
    public void drained(DormantWatch watch) {
        drained.put(watch.player(), new Drained(connectionId(watch), platformPlayer(watch)));
    }

    @Override
    public Collection<UUID> unwatched() {
        List<UUID> players = new ArrayList<>(drained.keySet());
        skipped.removeIf(channel -> !channel.isActive());
        for (Channel channel : skipped) {
            if (channel.pipeline().get("packet_handler") instanceof Connection connection
                    && connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener
                    && listener.player != null) players.add(listener.player.getUUID());
        }
        return players;
    }

    private static @Nullable UUID connectionId(DormantWatch watch) {
        return watch.state().get("connection-id") instanceof String id ? UUID.fromString(id) : null;
    }

    private static @Nullable PlatformPlayer platformPlayer(DormantWatch watch) {
        return watch.platformPlayer() instanceof PlatformPlayer known ? known : null;
    }
}
