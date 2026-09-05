package ac.cult.cultac.network;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.event.UserConnectEvent;
import ac.cult.cultac.network.event.UserDisconnectEvent;
import ac.cult.cultac.network.event.UserLifecycleListener;
import ac.cult.cultac.network.event.UserLoginEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.packet.PreservedClientboundBundlePacket;
import ac.cult.cultac.network.packet.LegacyViaInputBridge;
import ac.cult.cultac.network.protocol.util.viaversion.ViaVersionUtil;
import ac.cult.cultac.utils.anticheat.LogUtil;
import com.noxcrew.packet.PacketApi;
import com.noxcrew.packet.PacketContext;
import com.noxcrew.packet.PacketContextHandlerFunction;
import com.noxcrew.packet.PacketHandlerUnregisterer;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class CultNetworkManager implements Listener {
    private static final String HANDLER_NAME = "cult-nms-listener";
    private static final AttributeKey<CopyOnWriteArrayList<Packet<?>>> SILENT_INBOUND = AttributeKey.valueOf("cult-silent-inbound");
    private static final AttributeKey<CopyOnWriteArrayList<Packet<?>>> SILENT_OUTBOUND = AttributeKey.valueOf("cult-silent-outbound");

    private final Map<Object, User> usersByConnection = new ConcurrentHashMap<>();
    private final Map<UUID, User> currentUsersByUuid = new ConcurrentHashMap<>();
    private final Set<User> loggedInUsers = ConcurrentHashMap.newKeySet();
    private final Set<Object> ignoredConnections = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<UserLifecycleListener> lifecycleListeners = new CopyOnWriteArrayList<>();
    private final Map<Class<? extends Packet<?>>, CopyOnWriteArrayList<ReceiveHandlerRegistration>> earlyReceiveHandlers = new ConcurrentHashMap<>();
    private final Map<Class<? extends Packet<?>>, CopyOnWriteArrayList<ReceiveHandlerRegistration>> receiveHandlers = new ConcurrentHashMap<>();
    private final Map<Class<? extends Packet<?>>, CopyOnWriteArrayList<SendHandlerRegistration>> sendHandlers = new ConcurrentHashMap<>();
    private final Map<Class<? extends Packet<?>>, PacketReceiveRoute> earlyReceiveRoutes = new ConcurrentHashMap<>();
    private final Map<Class<? extends Packet<?>>, PacketReceiveRoute> receiveRoutes = new ConcurrentHashMap<>();
    private final Map<Class<? extends Packet<?>>, PacketSendRoute> sendRoutes = new ConcurrentHashMap<>();
    private final Map<Class<? extends Packet<?>>, PacketHandlerUnregisterer> packetHandlerUnregisterers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ReceiveHandlerRegistration> receiveTaps = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SendHandlerRegistration> sendTaps = new CopyOnWriteArrayList<>();
    private final PacketApi packetApi = new PacketApi(HANDLER_NAME, false);
    private final PacketContextHandlerFunction<Connection, Packet<?>> noxesiumPacketHandler = new NoxesiumPacketBridge(this);
    private final AtomicLong registrationOrder = new AtomicLong();
    private final Field configurationProfileField = resolveConfigurationProfileField();
    private final Method profileIdMethod = resolveProfileMethod("id", "getId");
    private final Method profileNameMethod = resolveProfileMethod("name", "getName");
    private final Field connectionChannelField = resolveConnectionChannelField();
    private volatile PacketReceiveRoute receiveTapRoute = PacketReceiveRoute.EMPTY;
    private volatile PacketSendRoute sendTapRoute = PacketSendRoute.EMPTY;

    private JavaPlugin plugin;
    private boolean started;

    /**
     * Hooks new connections into the packet interceptor. Runs at plugin load so the
     * hook is in place before anything else snapshots the server's connection
     * initializer (Geyser captures it at enable time for its local channels).
     */
    public void load(JavaPlugin plugin) {
        if (this.plugin != null) {
            return;
        }

        this.plugin = plugin;
        packetApi.register(plugin);
    }

    public void start(JavaPlugin plugin) {
        if (started) {
            return;
        }
        started = true;

        load(plugin);
        registerInternalPacketTypes();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        logPacketRegistrations();
    }

    public void stop() {
        started = false;
        HandlerList.unregisterAll(this);
        packetApi.unregister();
        unregisterPacketBridgeHandlers();

        for (User user : Set.copyOf(usersByConnection.values())) {
            disconnect(user, user.getPlayer());
        }

        usersByConnection.clear();
        currentUsersByUuid.clear();
        ignoredConnections.clear();
        loggedInUsers.clear();
        lifecycleListeners.clear();
        earlyReceiveHandlers.clear();
        receiveHandlers.clear();
        sendHandlers.clear();
        earlyReceiveRoutes.clear();
        receiveRoutes.clear();
        sendRoutes.clear();
        receiveTaps.clear();
        sendTaps.clear();
        receiveTapRoute = PacketReceiveRoute.EMPTY;
        sendTapRoute = PacketSendRoute.EMPTY;
        plugin = null;
    }

    public void registerListener(UserLifecycleListener listener) {
        lifecycleListeners.add(listener);
        lifecycleListeners.sort(Comparator.comparingInt(value -> value.getPriority().ordinal()));
    }

    public User getUser(Player player) {
        if (player == null) {
            return null;
        }
        Connection connection = currentPlayerConnection(player);
        if (connection == null) {
            return null;
        }

        User user = usersByConnection.get(connection);
        if (user == null) {
            return null;
        }
        return player.getUniqueId().equals(user.getUUID()) ? user : null;
    }

    public User getUser(UUID uuid) {
        return uuid == null ? null : currentUsersByUuid.get(uuid);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Connection connection = currentPlayerConnection(player);
        if (connection == null || ignoredConnections.contains(connection)) {
            return;
        }

        User user = usersByConnection.get(connection);
        if (user == null) {
            user = registerConnection(player.getUniqueId(), player.getName(), connection);
        }
        if (user == null || !player.getUniqueId().equals(user.getUUID())) {
            return;
        }

        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
        completeLogin(user, player, serverPlayer);
    }

    public void clearListeners() {
        lifecycleListeners.clear();
        earlyReceiveHandlers.clear();
        receiveHandlers.clear();
        sendHandlers.clear();
        earlyReceiveRoutes.clear();
        receiveRoutes.clear();
        sendRoutes.clear();
        receiveTaps.clear();
        sendTaps.clear();
        receiveTapRoute = PacketReceiveRoute.EMPTY;
        sendTapRoute = PacketSendRoute.EMPTY;
    }

    public <T extends Packet<?>> void registerEarlyReceiveHandler(Class<T> packetType, PacketListenerPriority priority,
                                                                  PacketReceiveHandler<? super T> handler) {
        registerEarlyReceiveHandlerUnchecked(packetType, priority, handler);
        ensurePacketBridge(packetType);
    }

    public <T extends Packet<?>> void registerReceiveHandler(Class<T> packetType, PacketListenerPriority priority,
                                                             PacketReceiveHandler<? super T> handler) {
        registerReceiveHandlerUnchecked(packetType, priority, handler);
        ensurePacketBridge(packetType);
    }

    public <T extends Packet<?>> void registerSendHandler(Class<T> packetType, PacketListenerPriority priority,
                                                          PacketSendHandler<? super T> handler) {
        registerSendHandlerUnchecked(packetType, priority, handler);
        ensurePacketBridge(packetType);
    }

    public void registerReceiveTap(PacketListenerPriority priority, PacketReceiveHandler<Packet<?>> handler) {
        receiveTaps.add(new ReceiveHandlerRegistration(priority, registrationOrder.getAndIncrement(), handler));
        sortReceiveHandlers(receiveTaps);
        receiveTapRoute = buildReceiveRoute(receiveTaps);
    }

    public void registerSendTap(PacketListenerPriority priority, PacketSendHandler<Packet<?>> handler) {
        sendTaps.add(new SendHandlerRegistration(priority, registrationOrder.getAndIncrement(), handler));
        sortSendHandlers(sendTaps);
        sendTapRoute = buildSendRoute(sendTaps);
    }

    private void registerInternalPacketTypes() {
        ensurePacketBridge(net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket.class);
        ensurePacketBridge(net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket.class);
        ensurePacketBridge(net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket.class);
        ensurePacketBridge(net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends Packet<?>> void registerEarlyReceiveHandlerUnchecked(
            Class<T> packetType,
            PacketListenerPriority priority,
            PacketReceiveHandler<? super T> handler
    ) {
        CopyOnWriteArrayList<ReceiveHandlerRegistration> handlers =
                earlyReceiveHandlers.computeIfAbsent((Class<? extends Packet<?>>) packetType,
                        ignored -> new CopyOnWriteArrayList<>());
        handlers.add(new ReceiveHandlerRegistration(priority, registrationOrder.getAndIncrement(),
                (PacketReceiveHandler) handler));
        sortReceiveHandlers(handlers);
        earlyReceiveRoutes.put((Class<? extends Packet<?>>) packetType, buildReceiveRoute(handlers));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends Packet<?>> void registerReceiveHandlerUnchecked(Class<T> packetType, PacketListenerPriority priority,
                                                                      PacketReceiveHandler<? super T> handler) {
        CopyOnWriteArrayList<ReceiveHandlerRegistration> handlers =
                receiveHandlers.computeIfAbsent((Class<? extends Packet<?>>) packetType, ignored -> new CopyOnWriteArrayList<>());
        handlers.add(new ReceiveHandlerRegistration(priority, registrationOrder.getAndIncrement(), (PacketReceiveHandler) handler));
        sortReceiveHandlers(handlers);
        receiveRoutes.put((Class<? extends Packet<?>>) packetType, buildReceiveRoute(handlers));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends Packet<?>> void registerSendHandlerUnchecked(Class<T> packetType, PacketListenerPriority priority,
                                                                   PacketSendHandler<? super T> handler) {
        CopyOnWriteArrayList<SendHandlerRegistration> handlers =
                sendHandlers.computeIfAbsent((Class<? extends Packet<?>>) packetType, ignored -> new CopyOnWriteArrayList<>());
        handlers.add(new SendHandlerRegistration(priority, registrationOrder.getAndIncrement(), (PacketSendHandler) handler));
        sortSendHandlers(handlers);
        sendRoutes.put((Class<? extends Packet<?>>) packetType, buildSendRoute(handlers));
    }

    private static void sortReceiveHandlers(CopyOnWriteArrayList<ReceiveHandlerRegistration> handlers) {
        handlers.sort(Comparator
                .comparingInt((ReceiveHandlerRegistration value) -> value.priority().ordinal())
                .thenComparingLong(ReceiveHandlerRegistration::order));
    }

    private static void sortSendHandlers(CopyOnWriteArrayList<SendHandlerRegistration> handlers) {
        handlers.sort(Comparator
                .comparingInt((SendHandlerRegistration value) -> value.priority().ordinal())
                .thenComparingLong(SendHandlerRegistration::order));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PacketReceiveRoute buildReceiveRoute(List<ReceiveHandlerRegistration> registrations) {
        PacketReceiveHandler<Packet<?>>[] handlers = new PacketReceiveHandler[registrations.size()];
        for (int i = 0; i < registrations.size(); i++) {
            handlers[i] = registrations.get(i).handler();
        }
        return PacketReceiveRoute.of(handlers);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PacketSendRoute buildSendRoute(List<SendHandlerRegistration> registrations) {
        PacketSendHandler<Packet<?>>[] handlers = new PacketSendHandler[registrations.size()];
        for (int i = 0; i < registrations.size(); i++) {
            handlers[i] = registrations.get(i).handler();
        }
        return PacketSendRoute.of(handlers);
    }

    private synchronized void unregisterPacketBridgeHandlers() {
        for (PacketHandlerUnregisterer unregisterer : packetHandlerUnregisterers.values()) {
            unregisterer.unregister();
        }
        packetHandlerUnregisterers.clear();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void ensurePacketBridge(Class<? extends Packet<?>> packetType) {
        packetHandlerUnregisterers.computeIfAbsent(packetType, type -> packetApi.registerHandler(
                (Class) type,
                Connection.class,
                PacketApi.DEFAULT_HANDLER_PRIORITY,
                noxesiumPacketHandler
        ));
    }

    private List<Packet<?>> handleNoxesiumPacket(PacketContext context, Connection connection, Packet<?> packet) {
        User user = findOrCreateUser(connection);
        if (user == null) {
            return List.of(packet);
        }

        PacketFlow flow = packet.type().flow();
        if (flow == PacketFlow.SERVERBOUND) {
            return handleServerboundPacket(user, packet);
        }
        return handleClientboundPacket(user, packet, context.insideBundle());
    }

    private List<Packet<?>> handleServerboundPacket(User user, Packet<?> packet) {
        ConnectionProtocol connectionState = resolveConnectionState(packet, user.getConnectionState());
        user.setConnectionState(connectionState);
        prepareForPlay(user, packet);
        tryCompleteLogin(user);

        if (consumeSilentPacket((Channel) user.getChannel(), SILENT_INBOUND, packet)) {
            return List.of(packet);
        }

        PacketReceiveRoute earlyRoute = earlyReceiveRoutes.get(packet.getClass());
        PacketReceiveRoute route = receiveRoutes.get(packet.getClass());
        PacketReceiveRoute tapRoute = receiveTapRoute;
        if ((earlyRoute == null || earlyRoute.isEmpty())
                && (route == null || route.isEmpty())
                && tapRoute.isEmpty()) {
            return List.of(packet);
        }

        ac.cult.cultac.player.CultPlayer player = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(user);
        if (player == null && tapRoute.isEmpty()) {
            return List.of(packet);
        }

        Packet<?> routedPacket = packet;
        PacketReceiveEvent event = new PacketReceiveEvent(user, routedPacket, connectionState);
        try {
            PacketReceivePipeline.dispatch(
                    player != null && earlyRoute != null ? earlyRoute : PacketReceiveRoute.EMPTY,
                    player != null && route != null ? route : PacketReceiveRoute.EMPTY,
                    tapRoute,
                    event,
                    player,
                    routedPacket
            );
            if (event.isCancelled()) {
                return Collections.emptyList();
            }

            Packet<?> result = event.getNmsPacket();
            if (result == null) {
                return Collections.emptyList();
            }
            if (shouldScheduleReceivePostTasks(event, result)) {
                schedulePostReceiveTasks(user, event.getPostTasks());
            }
            return List.of(result);
        } finally {
            ReferenceCountUtil.safeRelease(event.getByteBuf());
        }
    }

    static boolean shouldScheduleReceivePostTasks(PacketReceiveEvent event, Packet<?> forwardedPacket) {
        return event != null
                && !event.isCancelled()
                && forwardedPacket != null
                && !event.getPostTasks().isEmpty();
    }

    private List<Packet<?>> handleClientboundPacket(User user, Packet<?> packet, boolean insideBundle) {
        // MCP-Reborn ClientPacketListener#handleBundlePacket processes each sub-packet
        // in order. Cult's handlers need to observe that same logical packet stream;
        // otherwise bundled entity spawns/moves reach the client without updating
        // compensation state.
        if (packet instanceof BundlePacket<?> bundle) {
            if (consumeSilentPacket((Channel) user.getChannel(), SILENT_OUTBOUND, packet)) {
                return List.of(packet);
            }
            List<Packet<?>> result = new ArrayList<>();
            for (Packet<?> subPacket : bundle.subPackets()) {
                if (subPacket != null) {
                    result.addAll(handleClientboundPacket(user, subPacket, true));
                }
            }
            return result;
        }

        ConnectionProtocol connectionState = resolveConnectionState(packet, user.getEncoderState());
        user.setEncoderState(connectionState);
        tryCompleteLogin(user);

        if (consumeSilentPacket((Channel) user.getChannel(), SILENT_OUTBOUND, packet)) {
            return List.of(packet);
        }

        PacketSendRoute route = sendRoutes.get(packet.getClass());
        PacketSendRoute tapRoute = sendTapRoute;
        if ((route == null || route.isEmpty()) && tapRoute.isEmpty()) {
            return List.of(packet);
        }

        ac.cult.cultac.player.CultPlayer player = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(user);
        if (player == null && tapRoute.isEmpty()) {
            return List.of(packet);
        }

        Packet<?> routedPacket = packet;
        PacketSendEvent event = new PacketSendEvent(user, routedPacket, connectionState, insideBundle);
        if (route != null && player != null) {
            route.dispatch(event, player, routedPacket);
        }
        tapRoute.dispatch(event, player, event.getNmsPacket());
        if (event.isCancelled()) {
            return Collections.emptyList();
        }

        Packet<?> result = reencodePacket(event);
        schedulePostSendTasks(user, event.getTasksAfterSend(), event.getPostTasks());
        List<Packet<?>> packets = new ArrayList<>(
                event.getPacketsBeforeSend().size()
                        + event.getPacketsAfterSend().size()
                        + 1
        );
        packets.addAll(event.getPacketsBeforeSend());
        packets.addAll(flattenPacketResult(result));
        packets.addAll(event.getPacketsAfterSend());
        return packets;
    }

    private void logPacketRegistrations() {
        int earlyReceiveHandlerCount = earlyReceiveHandlers.values().stream().mapToInt(List::size).sum();
        int receiveHandlerCount = receiveHandlers.values().stream().mapToInt(List::size).sum();
        int sendHandlerCount = sendHandlers.values().stream().mapToInt(List::size).sum();

        LogUtil.info("Registered " + earlyReceiveHandlers.size() + " early serverbound packet classes with "
                + earlyReceiveHandlerCount + " handlers, " + receiveHandlers.size()
                + " ordinary serverbound packet classes with " + receiveHandlerCount + " handlers and " + sendHandlers.size()
                + " clientbound packet classes with " + sendHandlerCount + " handlers.");
        logPacketRegistrations("Early serverbound", earlyReceiveHandlers);
        logPacketRegistrations("Serverbound", receiveHandlers);
        logPacketRegistrations("Clientbound", sendHandlers);
    }

    private void logPacketRegistrations(String direction, Map<Class<? extends Packet<?>>, ? extends List<?>> handlers) {
        handlers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Class::getName)))
                .forEach(entry -> LogUtil.debug(direction + " " + entry.getKey().getName()
                        + " -> " + entry.getValue().size() + " handlers"));
    }

    List<HandlerSnapshot> receiveHandlerSnapshots(Class<? extends Packet<?>> packetType) {
        List<ReceiveHandlerRegistration> handlers = receiveHandlers.get(packetType);
        if (handlers == null) {
            return List.of();
        }
        return handlers.stream()
                .map(handler -> new HandlerSnapshot(handler.priority(), handler.order()))
                .toList();
    }

    List<HandlerSnapshot> sendHandlerSnapshots(Class<? extends Packet<?>> packetType) {
        List<SendHandlerRegistration> handlers = sendHandlers.get(packetType);
        if (handlers == null) {
            return List.of();
        }
        return handlers.stream()
                .map(handler -> new HandlerSnapshot(handler.priority(), handler.order()))
                .toList();
    }

    List<HandlerSnapshot> receiveTapSnapshots() {
        return receiveTaps.stream()
                .map(handler -> new HandlerSnapshot(handler.priority(), handler.order()))
                .toList();
    }

    List<HandlerSnapshot> sendTapSnapshots() {
        return sendTaps.stream()
                .map(handler -> new HandlerSnapshot(handler.priority(), handler.order()))
                .toList();
    }

    private User findOrCreateUser(Connection connection) {
        ConnectionProfile profile = connectionProfile(connection);
        if (profile == null || profile.uuid() == null || ignoredConnections.contains(connection)) {
            return null;
        }

        User connectionUser = usersByConnection.get(connection);
        if (connectionUser != null) {
            if (currentUsersByUuid.get(connectionUser.getUUID()) == connectionUser) {
                bindIfAvailable(connectionUser, profile);
            }
            return connectionUser;
        }

        User currentUser = currentUsersByUuid.get(profile.uuid());
        if (currentUser != null && currentUser.getConnection() == connection) {
            usersByConnection.putIfAbsent(connection, currentUser);
            bindIfAvailable(currentUser, profile);
            return currentUser;
        }

        if (!(connection.getPacketListener() instanceof ServerConfigurationPacketListenerImpl)
                && (profile.player() == null || !isPlayerConnection(profile.player(), connection))) {
            return null;
        }

        return registerConnection(profile.uuid(), profile.name(), connection);
    }

    private User registerConnection(UUID playerUUID, String playerName, Connection connection) {
        try {
            Channel channel = (Channel) connectionChannelField.get(connection);
            if (channel == null) {
                return null;
            }

            String name = playerName == null ? playerUUID.toString() : playerName;
            User user = new User(new User.Profile(playerUUID, name), null, null, connection, channel);
            user.setConnectionState(ConnectionProtocol.CONFIGURATION);
            user.setEncoderState(ConnectionProtocol.CONFIGURATION);

            User existingConnectionUser = usersByConnection.putIfAbsent(connection, user);
            if (existingConnectionUser != null) {
                return existingConnectionUser;
            }

            try {
                channel.closeFuture().addListener(future -> disconnect(user, user.getPlayer()));

                UserConnectEvent connectEvent = new UserConnectEvent(user, null);
                for (UserLifecycleListener listener : lifecycleListeners) {
                    listener.onUserConnect(connectEvent);
                }
                if (connectEvent.isCancelled()) {
                    usersByConnection.remove(connection, user);
                    ignoredConnections.add(connection);
                    CultAPI.INSTANCE.getPlayerDataManager().remove(user);
                    clearChannelState(user);
                    channel.closeFuture().addListener(future -> ignoredConnections.remove(connection));
                    return null;
                }

                // ServerConfigurationPacketListenerImpl exists only after login
                // success. PacketEvents created the CultPlayer at that boundary,
                // before configuration client-information/custom-payload packets.
                // Waiting for FinishConfiguration silently skipped those handlers.
                promoteCurrentUser(user);
                if (ViaVersionUtil.isAvailable()) {
                    LegacyViaInputBridge.install(user);
                }
            } catch (RuntimeException exception) {
                usersByConnection.remove(connection, user);
                currentUsersByUuid.remove(playerUUID, user);
                CultAPI.INSTANCE.getPlayerDataManager().remove(user);
                clearChannelState(user);
                throw exception;
            }
            return user;
        } catch (ReflectiveOperationException exception) {
            LogUtil.warn("Failed to create Cult user for " + playerName);
            exception.printStackTrace();
            return null;
        }
    }

    private void promoteCurrentUser(User user) {
        UUID playerUUID = user.getUUID();
        User previous = currentUsersByUuid.get(playerUUID);
        if (previous == user) {
            CultAPI.INSTANCE.getPlayerDataManager().addUser(user);
            return;
        }

        CultAPI.INSTANCE.getPlayerDataManager().addUser(user);
        currentUsersByUuid.put(playerUUID, user);
    }

    private void prepareForPlay(User user, Packet<?> packet) {
        if (packet instanceof ServerboundFinishConfigurationPacket) {
            // Idempotent fallback for users recovered after an unusual late bind.
            promoteCurrentUser(user);
        }
    }

    private void completeLogin(User user, Player player) {
        if (!loggedInUsers.add(user)) {
            return;
        }

        UserLoginEvent loginEvent = new UserLoginEvent(user, player);
        for (UserLifecycleListener listener : lifecycleListeners) {
            listener.onUserLogin(loginEvent);
        }
    }

    private void tryCompleteLogin(User user) {
        if (user == null || usersByConnection.get(connectionKey(user)) != user) {
            return;
        }
        if (!(user.getConnection().getPacketListener() instanceof ServerGamePacketListenerImpl listener)) {
            return;
        }

        ServerPlayer serverPlayer = listener.player;
        if (serverPlayer == null) {
            return;
        }

        Player player = Bukkit.getPlayer(user.getUUID());
        if (player == null) {
            return;
        }
        completeLogin(user, player, serverPlayer);
    }

    private void completeLogin(User user, Player player, ServerPlayer serverPlayer) {
        if (!isPlayerConnection(player, user.getConnection())) {
            return;
        }

        if (loggedInUsers.contains(user)) {
            return;
        }

        user.bind(player, serverPlayer);
        if (currentUsersByUuid.get(user.getUUID()) != user) {
            promoteCurrentUser(user);
        }
        updateCultPlayerBinding(user, player, serverPlayer);
        completeLogin(user, player);
    }

    private void bindIfAvailable(User user, ConnectionProfile profile) {
        if (profile.player() == null || profile.serverPlayer() == null) {
            return;
        }

        if (user.getPlayer() == profile.player() && user.getHandle() == profile.serverPlayer()) {
            return;
        }

        user.bind(profile.player(), profile.serverPlayer());
        updateCultPlayerBinding(user, profile.player(), profile.serverPlayer());
    }

    private void updateCultPlayerBinding(User user, Player player, ServerPlayer serverPlayer) {
        ac.cult.cultac.player.CultPlayer cultPlayer = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(user);
        if (cultPlayer == null) {
            return;
        }

        cultPlayer.updateServerPlayerBinding(player, serverPlayer);
        cultPlayer.updatePermissions();
    }

    private void disconnect(User disconnectingUser, Player player) {
        if (disconnectingUser == null) {
            return;
        }

        UUID uuid = disconnectingUser.getUUID();
        Object connection = connectionKey(disconnectingUser);
        ignoredConnections.remove(connection);

        boolean removedConnection = usersByConnection.remove(connection, disconnectingUser);
        boolean removedCurrent = currentUsersByUuid.remove(uuid, disconnectingUser);
        boolean removedLoggedIn = loggedInUsers.remove(disconnectingUser);
        if (removedCurrent) {
            CultAPI.INSTANCE.getPlayerDataManager().clearExemptions(disconnectingUser);
        }

        if (!removedConnection && !removedCurrent && !removedLoggedIn) {
            clearChannelState(disconnectingUser);
            return;
        }

        if (removedLoggedIn) {
            UserDisconnectEvent disconnectEvent = new UserDisconnectEvent(disconnectingUser, player);
            for (UserLifecycleListener listener : lifecycleListeners) {
                listener.onUserDisconnect(disconnectEvent);
            }
        } else {
            // Configuration-stage Users are already tracked after login success.
            // PlayerDataManager is connection-keyed, so cleanup can only remove
            // this exact User and cannot evict another connection with the same UUID.
            CultAPI.INSTANCE.getPlayerDataManager().onDisconnect(disconnectingUser);
        }

        clearChannelState(disconnectingUser);
    }

    private static Connection currentPlayerConnection(Player player) {
        if (!(player instanceof CraftPlayer craftPlayer)) {
            return null;
        }
        if (craftPlayer.getHandle().connection == null) {
            return null;
        }
        return craftPlayer.getHandle().connection.connection;
    }

    private static boolean isPlayerConnection(Player player, Connection connection) {
        return currentPlayerConnection(player) == connection;
    }

    private static Object connectionKey(User user) {
        Connection connection = user.getConnection();
        return connection == null ? user : connection;
    }

    private ConnectionProfile connectionProfile(Connection connection) {
        if (connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener && listener.player != null) {
            ServerPlayer serverPlayer = listener.player;
            Player player = serverPlayer.getBukkitEntity();
            return new ConnectionProfile(
                    serverPlayer.getUUID(),
                    player.getName(),
                    player,
                    serverPlayer
            );
        }

        if (connection.getPacketListener() instanceof ServerConfigurationPacketListenerImpl configurationListener) {
            try {
                Object profile = configurationProfileField.get(configurationListener);
                if (profile == null) {
                    return null;
                }
                UUID uuid = (UUID) profileIdMethod.invoke(profile);
                String name = (String) profileNameMethod.invoke(profile);
                return new ConnectionProfile(uuid, name, null, null);
            } catch (ReflectiveOperationException exception) {
                LogUtil.warn("Failed to read configuration profile");
                exception.printStackTrace();
            }
        }

        return null;
    }

    private void schedulePostSendTasks(User user, List<Runnable> tasksAfterSend, List<Runnable> postTasks) {
        if (tasksAfterSend.isEmpty() && postTasks.isEmpty()) {
            return;
        }

        Runnable task = () -> {
            for (Runnable runnable : tasksAfterSend) {
                runDeferredPacketTask("tasksAfterSend", runnable);
            }
            for (Runnable runnable : postTasks) {
                runDeferredPacketTask("postTasks", runnable);
            }
        };

        Channel channel = (Channel) user.getChannel();
        if (channel == null) {
            task.run();
            return;
        }
        channel.eventLoop().execute(task);
    }

    private void schedulePostReceiveTasks(User user, List<Runnable> postTasks) {
        if (postTasks.isEmpty()) {
            return;
        }

        Runnable task = () -> {
            for (Runnable runnable : postTasks) {
                runDeferredPacketTask("postReceiveTasks", runnable);
            }
        };

        Channel channel = (Channel) user.getChannel();
        if (channel == null) {
            task.run();
            return;
        }
        channel.eventLoop().execute(task);
    }

    static void runDeferredPacketTask(String phase, Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable throwable) {
            try {
                LogUtil.warn("Deferred packet task failed in " + phase + ": " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            } catch (Throwable ignored) {
            }
            throwable.printStackTrace();
        }
    }

    public Object getChannel(UUID uuid) {
        User user = getUser(uuid);
        return user == null ? null : user.getChannel();
    }

    public void sendPacket(Object channel, Object packet, boolean silent) {
        if (!(channel instanceof Channel nettyChannel)) {
            return;
        }
        Packet<?> nmsPacket = unwrapPacket(packet);
        if (nmsPacket == null) {
            return;
        }

        Runnable task = () -> {
            if (silent) {
                addSilentOutboundPacket(nettyChannel, nmsPacket);
            }
            nettyChannel.writeAndFlush(nmsPacket);
        };

        if (nettyChannel.eventLoop().inEventLoop()) {
            task.run();
        } else {
            nettyChannel.eventLoop().execute(task);
        }
    }

    public void sendPacketWithSilentPackets(Object channel, Object packet, Collection<? extends Packet<?>> silentPackets) {
        if (!(channel instanceof Channel nettyChannel)) {
            return;
        }
        Packet<?> nmsPacket = unwrapPacket(packet);
        if (nmsPacket == null) {
            return;
        }

        Runnable task = () -> {
            for (Packet<?> silentPacket : silentPackets) {
                addSilentPacketExact(nettyChannel, SILENT_OUTBOUND, silentPacket);
            }
            nettyChannel.writeAndFlush(nmsPacket);
        };

        if (nettyChannel.eventLoop().inEventLoop()) {
            task.run();
        } else {
            nettyChannel.eventLoop().execute(task);
        }
    }

    public void receivePacket(Object channel, Object packet, boolean silent) {
        if (!(channel instanceof Channel nettyChannel)) {
            return;
        }
        Packet<?> nmsPacket = unwrapPacket(packet);
        if (nmsPacket == null) {
            return;
        }

        Runnable task = () -> {
            if (silent && (earlyReceiveHandlers.containsKey(nmsPacket.getClass())
                    || receiveHandlers.containsKey(nmsPacket.getClass()))) {
                getSilentPackets(nettyChannel, SILENT_INBOUND).add(nmsPacket);
            }
            nettyChannel.pipeline().fireChannelRead(nmsPacket);
        };

        if (nettyChannel.eventLoop().inEventLoop()) {
            task.run();
        } else {
            nettyChannel.eventLoop().execute(task);
        }
    }

    private Packet<?> unwrapPacket(Object packet) {
        if (packet instanceof Packet<?> nmsPacket) {
            return nmsPacket;
        }
        if (packet == null) {
            return null;
        }
        try {
            Method getPacket = packet.getClass().getMethod("getPacket");
            Object candidate = getPacket.invoke(packet);
            return candidate instanceof Packet<?> nmsPacket ? nmsPacket : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private ConnectionProtocol resolveConnectionState(Packet<?> packet, ConnectionProtocol currentState) {
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket) {
            return ConnectionProtocol.CONFIGURATION;
        }
        if (packet instanceof net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket) {
            return ConnectionProtocol.PLAY;
        }
        if (packet instanceof net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket) {
            return currentState;
        }
        if (packet instanceof ServerboundCustomPayloadPacket
                || packet instanceof ServerboundClientInformationPacket) {
            return currentState;
        }
        if (packet instanceof ClientboundPingPacket || packet instanceof ServerboundPongPacket) {
            return ConnectionProtocol.PLAY;
        }
        if (packet instanceof ClientboundKeepAlivePacket
                || packet instanceof ServerboundKeepAlivePacket
                || packet instanceof ServerboundResourcePackPacket) {
            return ConnectionProtocol.PLAY;
        }

        String packageName = packet.getClass().getPackageName();
        if (packageName.contains(".protocol.common.")) {
            return ConnectionProtocol.PLAY;
        }
        if (packageName.contains(".protocol.login.")) {
            return ConnectionProtocol.LOGIN;
        }
        if (packageName.contains(".protocol.configuration.")) {
            return ConnectionProtocol.CONFIGURATION;
        }
        if (packageName.contains(".protocol.status.")) {
            return ConnectionProtocol.STATUS;
        }
        return ConnectionProtocol.PLAY;
    }

    private Packet<?> reencodePacket(PacketSendEvent event) {
        Packet<?> packet = event.getNmsPacket();
        if (!event.shouldReEncode()) {
            return packet;
        }

        Object wrapper = event.getLastUsedWrapper();
        if (wrapper == null) {
            return packet;
        }

        try {
            Method getPacket = wrapper.getClass().getMethod("getPacket");
            Object candidate = getPacket.invoke(wrapper);
            return candidate instanceof Packet<?> nmsPacket ? nmsPacket : packet;
        } catch (ReflectiveOperationException ignored) {
            return packet;
        }
    }

    private static List<Packet<?>> flattenPacketResult(Packet<?> packet) {
        if (packet == null) {
            return Collections.emptyList();
        }
        if (!(packet instanceof BundlePacket<?> bundle)
                || packet instanceof PreservedClientboundBundlePacket) {
            return List.of(packet);
        }

        List<Packet<?>> packets = new ArrayList<>();
        for (Packet<?> subPacket : bundle.subPackets()) {
            if (subPacket != null) {
                packets.add(subPacket);
            }
        }
        return packets;
    }

    private void clearChannelState(User user) {
        if (ViaVersionUtil.isAvailable()) {
            LegacyViaInputBridge.remove(user);
        }
        Channel channel = (Channel) user.getChannel();
        if (channel == null) {
            return;
        }

        channel.attr(SILENT_INBOUND).set(null);
        channel.attr(SILENT_OUTBOUND).set(null);
    }

    private static CopyOnWriteArrayList<Packet<?>> getSilentPackets(Channel channel, AttributeKey<CopyOnWriteArrayList<Packet<?>>> key) {
        CopyOnWriteArrayList<Packet<?>> packets = channel.attr(key).get();
        if (packets != null) {
            return packets;
        }

        CopyOnWriteArrayList<Packet<?>> created = new CopyOnWriteArrayList<>();
        if (!channel.attr(key).compareAndSet(null, created)) {
            return channel.attr(key).get();
        }
        return created;
    }

    private void addSilentOutboundPacket(Channel channel, Packet<?> packet) {
        if (packet instanceof BundlePacket<?> bundle) {
            for (Packet<?> subPacket : bundle.subPackets()) {
                if (subPacket != null) {
                    addSilentOutboundPacket(channel, subPacket);
                }
            }
            return;
        }
        // PacketApi invokes Cult only for concrete classes with a registered
        // bridge. Marking unobserved packets would leave permanent silent entries.
        if (sendHandlers.containsKey(packet.getClass())) {
            addSilentPacketExact(channel, SILENT_OUTBOUND, packet);
        }
    }

    private static void addSilentPacketExact(Channel channel, AttributeKey<CopyOnWriteArrayList<Packet<?>>> key, Packet<?> packet) {
        getSilentPackets(channel, key).add(packet);
    }

    private static boolean consumeSilentPacket(Channel channel, AttributeKey<CopyOnWriteArrayList<Packet<?>>> key, Packet<?> packet) {
        CopyOnWriteArrayList<Packet<?>> packets = channel.attr(key).get();
        if (packets == null) {
            return false;
        }

        for (Packet<?> candidate : packets) {
            if (candidate == packet) {
                packets.remove(candidate);
                return true;
            }
        }

        return false;
    }

    private static Field resolveConfigurationProfileField() {
        try {
            Field field = ServerConfigurationPacketListenerImpl.class.getDeclaredField("gameProfile");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException("Failed to resolve Minecraft configuration profile field", exception);
        }
    }

    private static Method resolveProfileMethod(String... candidates) {
        for (String candidate : candidates) {
            try {
                return com.mojang.authlib.GameProfile.class.getMethod(candidate);
            } catch (NoSuchMethodException ignored) {
                // Authlib changed GameProfile from accessors to record-style methods.
            }
        }
        throw new IllegalStateException("Failed to resolve Authlib game profile accessor");
    }

    private static Field resolveConnectionChannelField() {
        try {
            Field field = Connection.class.getDeclaredField("channel");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException("Failed to resolve Minecraft connection channel field", exception);
        }
    }

    private record ReceiveHandlerRegistration(PacketListenerPriority priority, long order,
                                              PacketReceiveHandler<Packet<?>> handler) {
    }

    private record SendHandlerRegistration(PacketListenerPriority priority, long order,
                                           PacketSendHandler<Packet<?>> handler) {
    }

    private static final class NoxesiumPacketBridge implements PacketContextHandlerFunction<Connection, Packet<?>> {
        private final CultNetworkManager networkManager;

        private NoxesiumPacketBridge(CultNetworkManager networkManager) {
            this.networkManager = networkManager;
        }

        @Override
        public List<Packet<?>> invoke(PacketContext context, Connection receiver, Packet<?> packet) {
            return networkManager.handleNoxesiumPacket(context, receiver, packet);
        }
    }

    record HandlerSnapshot(PacketListenerPriority priority, long order) {
    }

    private record ConnectionProfile(UUID uuid, String name, Player player, ServerPlayer serverPlayer) {
    }
}
