package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.ConnectionContinuation;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.DormancyController;
import ac.cult.cultac.network.DormantWatch;
import ac.cult.cultac.network.PacketOwner;
import ac.cult.cultac.network.routing.NetworkModule;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.velocity.bridge.BridgeService;
import ac.cult.cultac.platform.velocity.cloud.CloudBackends;
import ac.cult.cultac.protocol.netty.CultDecoder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.player.PlayerSettings;
import com.velocitypowered.api.proxy.player.SkinParts;
import io.netty.channel.Channel;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * Dormant mode for the players on this proxy, through {@link DormancyController}. The setting for the backend server
 * a player is switching to decides at each reconfiguration acknowledgement; a dormant player attached again gets the
 * brand, client settings and plugin channels Velocity holds for them. A dormant Bedrock player loses only their CultAC
 * player and is checked again when they reconnect.
 */
final class VelocityDormancy implements DormancyController.Platform {
    private final ProxyServer proxy;
    private final VelocityTransport transport;
    private final BridgeService bridge;
    private final Supplier<@Nullable CloudBackends> cloudBackends;
    private volatile @Nullable Method inFlight;
    private volatile @Nullable Method clientChannels;

    VelocityDormancy(
            ProxyServer proxy,
            VelocityTransport transport,
            BridgeService bridge,
            Supplier<@Nullable CloudBackends> cloudBackends) {
        this.proxy = proxy;
        this.transport = transport;
        this.bridge = bridge;
        this.cloudBackends = cloudBackends;
    }

    @Override
    public @Nullable UUID player(CultConnection connection) {
        if (!(connection.platform() instanceof VelocityConnectionAdapter adapter)) return null;
        return adapter.nativePlayer().getUniqueId();
    }

    /** A Bedrock connection keeps CultAC's handlers and its Geyser session tap until it closes. */
    @Override
    public boolean swaps(CultConnection connection) {
        return connection.bedrockBridge() == null;
    }

    @Override
    public @Nullable String backend(UUID player) {
        Player online = proxy.getPlayer(player).orElse(null);
        return NetworkModule.backend(player, online == null ? null : current(online));
    }

    @Override
    public @Nullable String target(UUID player) {
        Player online = proxy.getPlayer(player).orElse(null);
        return online == null ? backend(player) : target(online);
    }

    @Override
    public String decoder() {
        return CultDecoder.NAME;
    }

    @Override
    public DormantWatch watch(CultConnection connection, boolean play) {
        var adapter = (VelocityConnectionAdapter) connection.platform();
        Player player = adapter.nativePlayer();
        return new DormantWatch(
                connection.channel(),
                adapter.getObservedProtocol(),
                player.getUniqueId(),
                player,
                adapter.playerBinding().player(),
                connection.dormant(),
                new int[0],
                play,
                DormancyController.hooks());
    }

    @Override
    public void removeHandlers(CultConnection connection) {
        transport.removeHandlers(connection);
    }

    @Override
    public void detached(UUID player) {
        bridge.dormant(player);
    }

    @Override
    public boolean accepting() {
        return transport.running();
    }

    @Override
    public void resume(DormantWatch watch) {
        if (watch.nativePlayer() instanceof Player player) attach(player, watch.channel(), watch.state());
    }

    @Override
    public void closed(DormantWatch watch) {
        UUID player = watch.player();
        bridge.awake(player);
        UUID connectionId = watch.state().get("connection-id") instanceof String id ? UUID.fromString(id) : null;
        PlatformPlayer platformPlayer = watch.platformPlayer() instanceof PlatformPlayer known
                ? known
                : watch.nativePlayer() == null
                        ? null
                        : CultAPI.INSTANCE.getPlatformPlayerFactory().getFromNativePlayerType(watch.nativePlayer());
        CultAPI.INSTANCE.getPlayerDataManager().dormantQuit(player, connectionId, platformPlayer);
    }

    /**
     * A player at PostLogin; true when they are dormant, by the server Velocity is connecting them to or, with none in
     * flight, as {@link DormancyController#dormant} decides for an unknown server, and got only a watch (Java) or
     * CultAC's handlers without a CultAC player (Bedrock). Anyone else is attached, and goes dormant once their
     * connection to a server where they are dormant completed. On the channel's event loop.
     */
    boolean joined(Player player, Channel channel, @Nullable PacketOwner bedrock) {
        if (!DormancyController.dormant(inFlight(player))) return false;
        if (bedrock != null) {
            bridge.dormant(player.getUniqueId());
            transport.attachDormant(player, channel, bedrock);
            return true;
        }
        if (VelocityProtocols.disconnectUnsupportedClient(player, channel)) return true;
        watch(player, channel, Map.of(DormantWatch.LOGIN_NOTIFIED, false), new int[0]);
        return true;
    }

    /** Releases a watched connection to a newer generation, removing the watch; null when this generation has none. */
    @Nullable
    Map<String, Object> release(Channel channel) {
        if (!channel.eventLoop().inEventLoop()
                || !(channel.pipeline().get(DormantWatch.NAME) instanceof DormantWatch watch)) return null;
        return watch.release();
    }

    /** Adopts a connection an older generation held dormant, at its reconfiguration acknowledgement. */
    void adopt(Player player, Channel channel, Map<String, Object> released) {
        Map<String, Object> state = new HashMap<>(released);
        state.remove(DormantWatch.DORMANT);
        int[] retired = state.remove(DormantWatch.RETIRED) instanceof int[] ids ? ids : new int[0];
        if (DormancyController.dormant(target(player))) {
            watch(player, channel, state, retired);
            return;
        }
        attach(player, channel, state);
    }

    /**
     * Takes over an inspected Java connection an older generation released at its reconfiguration acknowledgement as a
     * watch when its player is switching to a server where they are dormant, closing the session row that generation
     * kept open; false, changing nothing, when they are checked there.
     */
    boolean adoptDormant(Player player, Channel channel, Map<String, Object> released) {
        if (!DormancyController.dormant(target(player))) return false;
        Map<String, Object> state = new HashMap<>(released);
        state.put(DormantWatch.LOGIN_NOTIFIED, true);
        if (state.get("connection-id") instanceof String id)
            CultAPI.INSTANCE.getPlayerDataManager().dormantAdopted(player.getUniqueId(), UUID.fromString(id));
        watch(player, channel, state, new int[0]);
        return true;
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

    private void watch(Player player, Channel channel, Map<String, Object> state, int[] retired) {
        var watch = new DormantWatch(
                channel,
                VelocityProtocols.read(player, channel).observed(),
                player.getUniqueId(),
                player,
                null,
                state,
                retired,
                false,
                DormancyController.hooks());
        VelocityPacketPipeline.watch(channel.pipeline(), watch);
        bridge.dormant(player.getUniqueId());
    }

    private void attach(Player player, Channel channel, Map<String, Object> state) {
        boolean continued = Boolean.TRUE.equals(state.get(DormantWatch.LOGIN_NOTIFIED));
        bridge.awake(player.getUniqueId());
        transport.attach(
                player, channel, null, continued ? ConnectionContinuation.from(refreshed(state, player)) : null);
        bridge.adopted(player);
        CloudBackends backends = cloudBackends.get();
        if (backends != null) backends.adopted(player);
    }

    /** {@code captured} with the brand, client settings and plugin channels Velocity holds for the client. */
    private Map<String, Object> refreshed(Map<String, Object> captured, Player player) {
        Map<String, Object> state = new HashMap<>(captured);
        String brand = player.getClientBrand();
        if (brand != null) state.put("brand", brand);
        if (player.hasSentPlayerSettings()) {
            try {
                state.put("client-information", information(player.getPlayerSettings()));
            } catch (RuntimeException unreadable) {
                // The captured settings stand.
            }
        }
        Collection<String> channels = clientChannels(player);
        if (channels != null) state.put("registered-channels", List.copyOf(channels));
        return state;
    }

    private static Map<String, Object> information(PlayerSettings settings) {
        SkinParts skin = settings.getSkinParts();
        int parts = (skin.hasCape() ? 0x01 : 0)
                | (skin.hasJacket() ? 0x02 : 0)
                | (skin.hasLeftSleeve() ? 0x04 : 0)
                | (skin.hasRightSleeve() ? 0x08 : 0)
                | (skin.hasLeftPants() ? 0x10 : 0)
                | (skin.hasRightPants() ? 0x20 : 0)
                | (skin.hasHat() ? 0x40 : 0);
        return Map.of(
                "language",
                settings.getLocale().toString().toLowerCase(Locale.ROOT),
                "view-distance",
                (int) settings.getViewDistance(),
                "chat-visibility",
                switch (settings.getChatMode()) {
                    case COMMANDS_ONLY -> "SYSTEM";
                    case HIDDEN -> "HIDDEN";
                    default -> "FULL";
                },
                "chat-colors",
                settings.hasChatColors(),
                "model-customisation",
                parts,
                "main-hand",
                settings.getMainHand().name(),
                "text-filtering",
                settings.isTextFilteringEnabled(),
                "allows-listing",
                settings.isClientListingAllowed(),
                "particle-status",
                settings.getParticleStatus().name());
    }

    /** The plugin channels the client registered, as Velocity's connected player keeps them; null when unreadable. */
    private @Nullable Collection<String> clientChannels(Player player) {
        try {
            Method method = clientChannels;
            if (method == null) clientChannels = method = player.getClass().getMethod("getClientsideChannels");
            if (!(method.invoke(player) instanceof Collection<?> channels)) return null;
            List<String> names = new ArrayList<>();
            for (Object channel : channels) {
                if (channel instanceof String name) names.add(name);
            }
            return names;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return null;
        }
    }

    /** The backend server a player at a reconfiguration acknowledgement is switching to, else the one they are on. */
    private @Nullable String target(Player player) {
        String connecting = inFlight(player);
        return connecting != null ? connecting : backend(player.getUniqueId());
    }

    /** The backend server Velocity is connecting {@code player} to; null while none is in flight or it is unreadable. */
    private @Nullable String inFlight(Player player) {
        try {
            Method method = inFlight;
            if (method == null) inFlight = method = player.getClass().getMethod("getConnectionInFlight");
            if (method.invoke(player) instanceof ServerConnection server)
                return server.getServerInfo().getName();
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            // Velocity without the in-flight connection getter.
        }
        return null;
    }

    private static @Nullable String current(Player player) {
        return player.getCurrentServer()
                .map(server -> server.getServerInfo().getName())
                .orElse(null);
    }
}
