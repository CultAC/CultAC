package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.network.routing.MessageTransport;
import ac.cult.cultac.utils.anticheat.LogUtil;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.proxy.ProxyServer;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

/**
 * The network transport over RedisBungee's own pub/sub, reached by reflection through RedisBungee's class loader.
 * RedisBungee owns the connection and its reconnects. The channels stay registered after {@link #close}: every
 * runtime on this proxy shares RedisBungee's registrations.
 */
final class RedisBungeeTransport implements MessageTransport {
    private static final String API = "com.imaginarycode.minecraft.redisbungee.AbstractRedisBungeeAPI";
    private static final String EVENT = "com.imaginarycode.minecraft.redisbungee.events.PubSubMessageEvent";

    private final ProxyServer proxy;
    private final Object plugin;
    private final Object api;
    private final Method register, send;
    private final Method eventChannel, eventMessage;
    private final Class<Object> eventClass;
    private final ExecutorService sender;
    private @Nullable EventHandler<Object> handler;

    @SuppressWarnings("unchecked")
    private RedisBungeeTransport(ProxyServer proxy, Object plugin, ClassLoader loader) throws Exception {
        this.proxy = proxy;
        this.plugin = plugin;
        Class<?> type = Class.forName(API, true, loader);
        api = type.getMethod("getAbstractRedisBungeeAPI").invoke(null);
        if (api == null) throw new IllegalStateException("RedisBungee has not started");
        register = type.getMethod("registerPubSubChannels", String[].class);
        send = type.getMethod("sendChannelMessage", String.class, String.class);
        eventClass = (Class<Object>) Class.forName(EVENT, true, loader);
        eventChannel = eventClass.getMethod("getChannel");
        eventMessage = eventClass.getMethod("getMessage");
        sender = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "CultAC network RedisBungee publisher");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Null, with a warning when RedisBungee is installed, when its pub/sub cannot be reached. */
    static @Nullable RedisBungeeTransport create(ProxyServer proxy, Object plugin) {
        ClassLoader loader = loader(proxy);
        if (loader == null) return null;
        try {
            return new RedisBungeeTransport(proxy, plugin, loader);
        } catch (Exception | LinkageError failure) {
            LogUtil.warn("RedisBungee is installed but its pub/sub cannot carry network messages", failure);
            return null;
        }
    }

    /** RedisBungee's id for this proxy; null without RedisBungee. */
    static @Nullable String proxyId(ProxyServer proxy) {
        ClassLoader loader = loader(proxy);
        if (loader == null) return null;
        try {
            Class<?> type = Class.forName(API, true, loader);
            Object api = type.getMethod("getAbstractRedisBungeeAPI").invoke(null);
            return api == null ? null : (String) type.getMethod("getProxyId").invoke(api);
        } catch (Exception | LinkageError unavailable) {
            return null;
        }
    }

    private static @Nullable ClassLoader loader(ProxyServer proxy) {
        var container = proxy.getPluginManager().getPlugin("redisbungee").orElse(null);
        Object instance = container == null ? null : container.getInstance().orElse(null);
        return instance == null ? null : instance.getClass().getClassLoader();
    }

    @Override
    public String name() {
        return "RedisBungee";
    }

    @Override
    public synchronized void start(List<String> channels, Receiver receiver) {
        if (handler != null) return;
        try {
            register.invoke(api, (Object) channels.toArray(String[]::new));
        } catch (ReflectiveOperationException failure) {
            LogUtil.warn("RedisBungee refused the network channels", failure);
            return;
        }
        Set<String> subscribed = Set.copyOf(channels);
        handler = event -> {
            try {
                String channel = (String) eventChannel.invoke(event);
                if (subscribed.contains(channel)) receiver.receive(channel, (String) eventMessage.invoke(event));
            } catch (ReflectiveOperationException failure) {
                LogUtil.warn("Unreadable RedisBungee pub/sub message", failure);
            }
        };
        proxy.getEventManager().register(plugin, eventClass, handler);
    }

    @Override
    public void publish(String channel, String text) {
        try {
            sender.execute(() -> {
                try {
                    send.invoke(api, channel, text);
                } catch (ReflectiveOperationException failure) {
                    LogUtil.warn("RedisBungee could not publish a network message", failure);
                }
            });
        } catch (RejectedExecutionException closed) {
            // A closed transport sends nothing.
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (handler != null) proxy.getEventManager().unregister(plugin, handler);
            handler = null;
        }
        sender.shutdown();
        try {
            if (!sender.awaitTermination(2, TimeUnit.SECONDS)) sender.shutdownNow();
        } catch (InterruptedException interrupted) {
            sender.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
