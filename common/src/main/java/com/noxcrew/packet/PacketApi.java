package com.noxcrew.packet;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import net.kyori.adventure.key.Key;
import net.minecraft.ChatFormatting;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.network.ServerConnectionListener;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;

/**
 * Provides the basis for a packet listening, modification, and cancellation API.
 *
 * <p>Packet handlers can transform packet types into others, however packet types are only
 * processed at most once to avoid infinite nesting.</p>
 */
// Originally licensed under LGPL from Noxesium
// https://github.com/Noxcrew/noxesium/blob/main/paper/packet/src/main/kotlin/com/noxcrew/packet/PacketApi.kt
public final class PacketApi {
    public static final int DEFAULT_HANDLER_PRIORITY = 100;

    private static final Key CHANNEL_INITIALIZE_KEY = Key.key("cultac", "injector");
    private static final String CHANNEL_INITIALIZE_LISTENER_CLASS = "io.papermc.paper.network.ChannelInitializeListener";
    private static final String CHANNEL_INITIALIZE_LISTENER_HOLDER_CLASS = "io.papermc.paper.network.ChannelInitializeListenerHolder";

    private final String key;
    private final boolean kickForPacketErrors;
    private final Logger logger = LoggerFactory.getLogger(PacketApi.class);
    private final Map<Connection, PlayerConnectionHandler> connectionHandlers = new ConcurrentHashMap<>();
    private final Map<Class<?>, PacketHandlers<?>> packetHandlers = new ConcurrentHashMap<>();
    private final ThreadsafeMultimap<PacketListener, PacketHandlerUnregisterer> listenerUnregisterers = new ThreadsafeMultimap<>();

    private Plugin plugin;
    private volatile boolean registered;

    public PacketApi(String key) {
        this(key, true);
    }

    public PacketApi(String key, boolean kickForPacketErrors) {
        this.key = key;
        this.kickForPacketErrors = kickForPacketErrors;
    }

    public String getKey() {
        return key;
    }

    public boolean isKickForPacketErrors() {
        return kickForPacketErrors;
    }

    public boolean getKickForPacketErrors() {
        return kickForPacketErrors;
    }

    public boolean isRegistered() {
        return registered;
    }

    public boolean getRegistered() {
        return registered;
    }

    /** Registers the packet API. */
    public void register(Plugin plugin) {
        if (registered) {
            return;
        }
        registered = true;

        this.plugin = plugin;
        injectServerConnectionListener();
    }

    /** Unregisters the packet API. */
    public void unregister() {
        if (!registered) {
            return;
        }
        registered = false;

        removeChannelInitializeListener();
        for (Connection connection : Set.copyOf(connectionHandlers.keySet())) {
            unregisterConnection(connection, false);
        }
    }

    /**
     * Registers a packet handler for the specified packet type.
     *
     * @return a callback that unregisters the handler when called
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <R, T extends Packet<?>> PacketHandlerUnregisterer registerHandler(
            Class<T> type,
            Class<R> receiver,
            int priority,
            PacketHandlerFunction<R, T> handler
    ) {
        return registerHandler(
                type,
                receiver,
                priority,
                (context, handlerReceiver, packet) -> handler.invoke(handlerReceiver, packet)
        );
    }

    /**
     * Registers a packet handler for the specified packet type with access to bridge context.
     *
     * @return a callback that unregisters the handler when called
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <R, T extends Packet<?>> PacketHandlerUnregisterer registerHandler(
            Class<T> type,
            Class<R> receiver,
            int priority,
            PacketContextHandlerFunction<R, T> handler
    ) {
        PacketHandlerWithPriority<R, T> handlerWithPriority =
                new PacketHandlerWithPriority<>(handler, receiver, priority);
        PacketHandlers<T> handlers = (PacketHandlers<T>) packetHandlers.computeIfAbsent(
                type,
                ignored -> new PacketHandlers()
        );

        handlers.add(handlerWithPriority);
        return () -> handlers.remove(handlerWithPriority);
    }

    public <R, T extends Packet<?>> PacketHandlerUnregisterer registerHandler(
            Class<T> type,
            Class<R> receiver,
            PacketHandlerFunction<R, T> handler
    ) {
        return registerHandler(type, receiver, DEFAULT_HANDLER_PRIORITY, handler);
    }

    /** Returns whether handlers for packets of the given packet are registered. */
    public boolean hasHandlers(Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket bundlePacket) {
            for (Packet<?> nested : PacketBundleUtil.subPackets(bundlePacket)) {
                if (hasHandlers(nested)) {
                    return true;
                }
            }
            return false;
        }
        return packetHandlers.containsKey(packet.getClass());
    }

    /** Calls all handlers on the given input, returning the manipulated packet. */
    List<Packet<?>> handlePacket(PacketContext context, Packet<?> input) {
        if (!hasHandlers(input)) {
            return List.of(input);
        }

        LinkedList<Packet<?>> packets = new LinkedList<>();
        LinkedList<Class<?>> pendingTypes = new LinkedList<>();
        Set<Class<?>> checkedTypes = new HashSet<>();

        packets.add(input);
        pendingTypes.add(input.getClass());
        PacketContext dispatchContext = input instanceof ClientboundBundlePacket
                ? context.asBundled()
                : context;

        while (!pendingTypes.isEmpty()) {
            Class<?> type = pendingTypes.removeFirst();
            checkedTypes.add(type);

            if (type == ClientboundBundlePacket.class) {
                unpackBundles(packets, pendingTypes, checkedTypes);
            } else {
                PacketHandlers<?> handlers = packetHandlers.get(type);
                if (handlers != null) {
                    handlers.run(dispatchContext, type, packets, pendingTypes, checkedTypes);
                }
            }
        }
        return packets;
    }

    private void unpackBundles(LinkedList<Packet<?>> packets, LinkedList<Class<?>> pendingTypes, Set<Class<?>> checkedTypes) {
        int index = 0;
        while (index < packets.size()) {
            Packet<?> packet = packets.get(index);

            if (packet instanceof ClientboundBundlePacket bundlePacket) {
                packets.remove(index);

                int insertIndex = index;
                for (Packet<?> nested : PacketBundleUtil.subPackets(bundlePacket)) {
                    if (nested == null) {
                        continue;
                    }

                    packets.add(insertIndex++, nested);
                    if (nested instanceof ClientboundBundlePacket) {
                        continue;
                    }

                    queueNestedPacket(nested, pendingTypes, checkedTypes);
                }
            } else {
                index++;
            }
        }
    }

    private void runHandlers(
            PacketContext context,
            Class<?> type,
            LinkedList<Packet<?>> packets,
            LinkedList<Class<?>> pendingTypes,
            Set<Class<?>> checkedTypes,
            List<? extends PacketHandlerWithPriority<?, ?>> handlers
    ) {
        for (PacketHandlerWithPriority<?, ?> handlerWithPriority : handlers) {
            int index = 0;
            while (index < packets.size()) {
                Packet<?> packet = packets.get(index);
                if (!type.isInstance(packet)) {
                    index++;
                    continue;
                }

                List<Packet<?>> output;
                try {
                    output = Objects.requireNonNull(handlerWithPriority.handle(context, packet), "Packet handler returned null");
                } catch (Exception | LinkageError exception) {
                    if (kickForPacketErrors && plugin != null) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Error handling packet " + packet.getClass().getSimpleName() + ", kicking player!", exception);
                        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                            context.connection().disconnect(
                                    Component.literal("An error occurred while parsing packets").withStyle(ChatFormatting.RED)
                            );
                            return null;
                        });
                        return;
                    }

                    if (plugin != null) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Error handling packet " + packet.getClass().getSimpleName()
                                        + "; forwarding it because packet-error kicks are disabled.", exception);
                    } else {
                        logger.error("Error handling packet {}; forwarding it because packet-error kicks are disabled.",
                                packet.getClass().getSimpleName(), exception);
                    }
                    // Move past this packet for the failing handler. Retrying at the same index
                    // invokes the same handler with the same packet forever when kicks are disabled.
                    index++;
                    continue;
                }

                if (output.size() == 1) {
                    Packet<?> firstOutput = output.getFirst();
                    if (firstOutput == packet) {
                        index++;
                        continue;
                    }

                    if (firstOutput == null) {
                        packets.remove(index);
                        continue;
                    }

                    if (firstOutput.getClass() == type) {
                        packets.set(index, firstOutput);
                        index++;
                        continue;
                    }
                }

                packets.remove(index);
                int insertIndex = index;
                for (Packet<?> nested : output) {
                    if (nested == null) {
                        continue;
                    }

                    if (insertIndex == index) {
                        index++;
                    }

                    packets.add(insertIndex++, nested);
                    if (type.isInstance(nested)) {
                        continue;
                    }

                    queueNestedPacket(nested, pendingTypes, checkedTypes);
                }
            }
        }
    }

    private void queueNestedPacket(Packet<?> nested, LinkedList<Class<?>> pendingTypes, Set<Class<?>> checkedTypes) {
        Class<?> nestedType = nested.getClass();
        if (!hasHandlers(nested)) {
            return;
        }
        if (checkedTypes.contains(nestedType)) {
            logger.warn("Skipping packet handling of packet type {} due to nested handlers", nestedType);
            return;
        }
        pendingTypes.add(nestedType);
    }

    /**
     * Registers all methods on the given listener that have the {@link PacketHandler} annotation
     * as packet handlers. Private methods as well as methods on superclasses are respected.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void registerListener(PacketListener listener) {
        Class<?> clazz = listener.getClass();

        while (clazz != null && PacketListener.class.isAssignableFrom(clazz)) {
            for (Method method : clazz.getDeclaredMethods()) {
                method.setAccessible(true);
                PacketHandler annotation = method.getAnnotation(PacketHandler.class);
                if (annotation == null) {
                    continue;
                }

                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length != 2
                        || !isReceiverType(parameterTypes[0])
                        || !Packet.class.isAssignableFrom(parameterTypes[1])
                        || (!Packet.class.isAssignableFrom(method.getReturnType())
                        && !List.class.isAssignableFrom(method.getReturnType()))) {
                    throw new IllegalArgumentException("PacketHandler " + method + " on " + clazz
                            + " doesn't match the PacketHandlerFunction interface "
                            + "(2 parameters, player and packet, returns packet or list of packets)");
                }

                boolean multiple = List.class.isAssignableFrom(method.getReturnType());
                PacketHandlerUnregisterer unregisterer = registerHandler(
                        (Class<Packet<?>>) parameterTypes[1],
                        parameterTypes[0],
                        annotation.priority(),
                        (receiver, packet) -> invokeListener(listener, method, multiple, receiver, packet)
                );

                listenerUnregisterers.put(listener, unregisterer);
            }

            clazz = clazz.getSuperclass();
        }
    }

    /** Unregisters all packet handlers that were previously registered for the given listener. */
    public void unregisterListener(PacketListener listener) {
        for (PacketHandlerUnregisterer unregisterer : listenerUnregisterers.removeAll(listener)) {
            unregisterer.unregister();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Packet<?>> invokeListener(
            PacketListener listener,
            Method method,
            boolean multiple,
            Object receiver,
            Packet<?> packet
    ) throws ReflectiveOperationException {
        if (multiple) {
            return (List<Packet<?>>) method.invoke(listener, receiver, packet);
        }

        Object result = method.invoke(listener, receiver, packet);
        if (result == null) {
            return Collections.emptyList();
        }
        if (result instanceof ClientboundBundlePacket bundlePacket) {
            List<Packet<?>> packets = new ArrayList<>();
            for (Packet<?> nested : PacketBundleUtil.subPackets(bundlePacket)) {
                packets.add(nested);
            }
            return packets;
        }
        return List.of((Packet<?>) result);
    }

    private static boolean isReceiverType(Class<?> type) {
        return Player.class.isAssignableFrom(type)
                || net.minecraft.world.entity.player.Player.class.isAssignableFrom(type)
                || Connection.class.isAssignableFrom(type);
    }

    /**
     * Registers the interceptor through Paper's channel initialize listener API so it
     * runs whenever the server initializes a connection channel, after the vanilla
     * pipeline has been set up and before the first packet is sent or received. This
     * covers every connection the server accepts, including downstream connections
     * other plugins run through the vanilla child initializer (Geyser's local
     * channels are initialized through this same path).
     */
    private void injectServerConnectionListener() {
        try {
            Class<?> listenerClass = Class.forName(CHANNEL_INITIALIZE_LISTENER_CLASS);
            Class<?> holderClass = Class.forName(CHANNEL_INITIALIZE_LISTENER_HOLDER_CLASS);
            Object listener = Proxy.newProxyInstance(PacketApi.class.getClassLoader(), new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        if ("afterInitChannel".equals(method.getName())) {
                            injectConnection((Channel) args[0]);
                            return null;
                        }
                        return method.invoke(proxy, args);
                    });
            Method addListenerMethod = holderClass.getDeclaredMethod("addListener", Key.class, listenerClass);
            addListenerMethod.invoke(null, CHANNEL_INITIALIZE_KEY, listener);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Unable to register the Paper channel initialize listener; packet interception is unavailable!",
                    exception
            );
        }

        ServerConnectionListener connectionListener = ((CraftServer) Bukkit.getServer()).getServer().getConnection();
        if (connectionListener != null) {
            // Connections older than this registration (plugin reload) never pass the
            // initializer again; inject into them directly.
            for (Connection connection : connectionListener.getConnections()) {
                try {
                    registerConnection(connection, connection.channel);
                } catch (RuntimeException exception) {
                    logger.warn("Failed to set up interceptor for an existing connection", exception);
                }
            }
        }
    }

    private void removeChannelInitializeListener() {
        try {
            Class<?> holderClass = Class.forName(CHANNEL_INITIALIZE_LISTENER_HOLDER_CLASS);
            Method removeListenerMethod = holderClass.getDeclaredMethod("removeListener", Key.class);
            removeListenerMethod.invoke(null, CHANNEL_INITIALIZE_KEY);
        } catch (ReflectiveOperationException exception) {
            logger.error("Failed to remove the Paper channel initialize listener", exception);
        }
    }

    /** Adds the interceptor to a freshly initialized connection channel. */
    private void injectConnection(Channel channel) {
        if (!registered) {
            return;
        }
        ChannelHandler packetHandler = channel.pipeline().get(PlayerConnectionHandler.MINECRAFT_PACKET_HANDLER_KEY);
        if (!(packetHandler instanceof Connection connection)) {
            return;
        }
        try {
            // The vanilla initializer assigns Connection#channel in channelActive, which
            // fires only after this initializer chain completes; use the channel being
            // initialized instead.
            registerConnection(connection, channel);
        } catch (RuntimeException exception) {
            logger.warn("Failed to set up interceptor for a new connection", exception);
        }
    }

    /** Registers a connection handler for a connection. */
    private void registerConnection(Connection connection, Channel channel) {
        if (connection == null || channel == null) {
            return;
        }

        PlayerConnectionHandler handler = new PlayerConnectionHandler(key, this, connection, channel);
        PlayerConnectionHandler existing = connectionHandlers.putIfAbsent(connection, handler);
        if (existing == null) {
            try {
                handler.register();
                channel.closeFuture().addListener(future -> unregisterConnection(connection, true));
            } catch (RuntimeException exception) {
                connectionHandlers.remove(connection, handler);
                throw exception;
            }
        }
    }

    /** Unregisters the connection handler for a connection. */
    private void unregisterConnection(Connection connection, boolean disconnect) {
        if (connection == null) {
            return;
        }

        PlayerConnectionHandler handler = connectionHandlers.remove(connection);
        if (handler == null) {
            return;
        }

        handler.unregister(disconnect);
    }

    /** Sends the given packets to a player. */
    public static void sendPacket(Player player, Packet<?>... packets) {
        PacketApiKt.sendPacket(player, packets);
    }

    /** Sends the given packets to a collection of players. */
    public static void sendPacket(Collection<? extends Player> players, Packet<?>... packets) {
        PacketApiKt.sendPacket(players, packets);
    }

    /** Sends the given packets to a player. */
    public static void sendPacket(Player player, Iterable<? extends Packet<?>> packets) {
        PacketApiKt.sendPacket(player, packets);
    }

    /** Sends the given packets to players. */
    public static void sendPacket(Iterable<? extends Player> players, Iterable<? extends Packet<?>> packets) {
        PacketApiKt.sendPacket(players, packets);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static ClientboundBundlePacket bundle(List<Packet<?>> packets) {
        return new ClientboundBundlePacket((Iterable<Packet<? super ClientGamePacketListener>>) (Iterable) packets);
    }

    private final class PacketHandlers<P extends Packet<?>> {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private final List<PacketHandlerWithPriority<?, P>> handlers = new ArrayList<>();

        void add(PacketHandlerWithPriority<?, P> handler) {
            lock.writeLock().lock();
            try {
                handlers.add(handler);
                handlers.sort(null);
            } finally {
                lock.writeLock().unlock();
            }
        }

        void remove(PacketHandlerWithPriority<?, P> handler) {
            lock.writeLock().lock();
            try {
                handlers.remove(handler);
                handlers.sort(null);
            } finally {
                lock.writeLock().unlock();
            }
        }

        void run(
                PacketContext context,
                Class<?> type,
                LinkedList<Packet<?>> packets,
                LinkedList<Class<?>> pendingTypes,
                Set<Class<?>> checkedTypes
        ) {
            lock.readLock().lock();
            try {
                runHandlers(context, type, packets, pendingTypes, checkedTypes, handlers);
            } finally {
                lock.readLock().unlock();
            }
        }
    }

    private static final class PacketHandlerWithPriority<R, T extends Packet<?>>
            implements Comparable<PacketHandlerWithPriority<?, ?>> {
        private final PacketContextHandlerFunction<R, T> handler;
        private final Class<R> receiverType;
        private final int priority;
        private final UUID identifier = UUID.randomUUID();

        private PacketHandlerWithPriority(PacketContextHandlerFunction<R, T> handler, Class<R> receiverType, int priority) {
            this.handler = handler;
            this.receiverType = receiverType;
            this.priority = priority;
        }

        @SuppressWarnings("unchecked")
        List<Packet<?>> handle(PacketContext context, Packet<?> packet) throws Exception {
            T castPacket = (T) packet;
            Object receiver;
            Connection connection = context.connection();

            if (Player.class.isAssignableFrom(receiverType)) {
                receiver = connection.getPlayer() == null ? null : connection.getPlayer().getBukkitEntity();
            } else if (net.minecraft.world.entity.player.Player.class.isAssignableFrom(receiverType)) {
                receiver = connection.getPlayer();
            } else if (Connection.class.isAssignableFrom(receiverType)) {
                receiver = connection;
            } else {
                return List.of(packet);
            }

            if (receiver == null) {
                return List.of(packet);
            }
            return handler.invoke(context, (R) receiver, castPacket);
        }

        @Override
        public int compareTo(PacketHandlerWithPriority<?, ?> other) {
            return Integer.compare(priority, other.priority);
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || (other instanceof PacketHandlerWithPriority<?, ?> packetHandler
                    && identifier.equals(packetHandler.identifier));
        }

        @Override
        public int hashCode() {
            return identifier.hashCode();
        }
    }
}
