package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.commands.CultProfile;
import ac.cult.cultac.command.profile.RemoteProfiles;
import ac.cult.cultac.player.CultPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.proxy.ProxyServer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jetbrains.annotations.Nullable;

/**
 * Asks the proxy holding a player for their profile over RedisBungee's pub/sub, when RedisBungee joins the proxies.
 * RedisBungee is optional and reached through its own class loader.
 */
final class RedisProfiles implements RemoteProfiles, AutoCloseable {
    static final String CHANNEL = "cultac:profile";
    private static final String API = "com.imaginarycode.minecraft.redisbungee.AbstractRedisBungeeAPI";
    private static final String EVENT = "com.imaginarycode.minecraft.redisbungee.events.PubSubMessageEvent";

    private final ProxyServer proxy;
    private final Object plugin;
    private final Logger logger;
    private final Object api;
    private final Method uuidFromName, nameFromUuid, playerOnline, proxyOf, serverOf, proxyId, send;
    private final Method eventChannel, eventMessage;
    private final Class<Object> eventClass;
    private final EventHandler<Object> handler = this::onEvent;
    private final Map<UUID, CompletableFuture<List<Component>>> pending = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private RedisProfiles(ProxyServer proxy, Object plugin, Logger logger, ClassLoader loader) throws Exception {
        this.proxy = proxy;
        this.plugin = plugin;
        this.logger = logger;
        Class<?> type = Class.forName(API, true, loader);
        api = type.getMethod("getAbstractRedisBungeeAPI").invoke(null);
        if (api == null) throw new IllegalStateException("RedisBungee has not started");
        uuidFromName = type.getMethod("getUuidFromName", String.class, boolean.class);
        nameFromUuid = type.getMethod("getNameFromUuid", UUID.class, boolean.class);
        playerOnline = type.getMethod("isPlayerOnline", UUID.class);
        proxyOf = type.getMethod("getProxy", UUID.class);
        serverOf = type.getMethod("getServerNameFor", UUID.class);
        proxyId = type.getMethod("getProxyId");
        send = type.getMethod("sendChannelMessage", String.class, String.class);
        eventClass = (Class<Object>) Class.forName(EVENT, true, loader);
        eventChannel = eventClass.getMethod("getChannel");
        eventMessage = eventClass.getMethod("getMessage");
        type.getMethod("registerPubSubChannels", String[].class).invoke(api, (Object) new String[] {CHANNEL});
        proxy.getEventManager().register(plugin, eventClass, handler);
    }

    /** Null when RedisBungee is not installed or cannot be reached. */
    static @Nullable RedisProfiles create(ProxyServer proxy, Object plugin, Logger logger) {
        var container = proxy.getPluginManager().getPlugin("redisbungee").orElse(null);
        Object instance = container == null ? null : container.getInstance().orElse(null);
        if (instance == null) return null;
        try {
            return new RedisProfiles(proxy, plugin, logger, instance.getClass().getClassLoader());
        } catch (Exception | LinkageError failure) {
            logger.log(Level.WARNING, "RedisBungee is installed but cross-proxy profiles are unavailable", failure);
            return null;
        }
    }

    @Override
    public @Nullable Remote locate(String name) {
        UUID uuid = (UUID) call(uuidFromName, name, false);
        if (uuid == null || !Boolean.TRUE.equals(call(playerOnline, uuid))) return null;
        String instance = (String) call(proxyOf, uuid);
        if (instance == null || instance.equals(call(proxyId))) return null;
        String known = (String) call(nameFromUuid, uuid, false);
        return new Remote(uuid, known == null ? name : known, instance, (String) call(serverOf, uuid));
    }

    @Override
    public CompletableFuture<List<Component>> request(Remote remote) {
        UUID id = UUID.randomUUID();
        var future = new CompletableFuture<List<Component>>();
        pending.put(id, future);
        future.whenComplete((ignored, failure) -> pending.remove(id));
        JsonObject request = new JsonObject();
        request.addProperty("type", "request");
        request.addProperty("id", id.toString());
        request.addProperty("from", (String) call(proxyId));
        request.addProperty("to", remote.instance());
        request.addProperty("player", remote.uuid().toString());
        publish(request);
        return future;
    }

    private void onEvent(Object event) {
        try {
            if (!CHANNEL.equals(eventChannel.invoke(event))) return;
            JsonObject message =
                    JsonParser.parseString((String) eventMessage.invoke(event)).getAsJsonObject();
            if (!message.get("to").getAsString().equals(call(proxyId))) return;
            UUID id = UUID.fromString(message.get("id").getAsString());
            switch (message.get("type").getAsString()) {
                case "request" ->
                    answer(
                            id,
                            message.get("from").getAsString(),
                            message.get("player").getAsString());
                case "response" -> {
                    var future = pending.get(id);
                    if (future == null) return;
                    List<Component> lines = new ArrayList<>();
                    for (JsonElement line : message.getAsJsonArray("lines")) {
                        lines.add(GsonComponentSerializer.gson().deserializeFromTree(line));
                    }
                    future.complete(lines);
                }
                default -> {}
            }
        } catch (Exception failure) {
            logger.log(Level.FINE, "Ignoring a malformed cross-proxy profile message", failure);
        }
    }

    /** An empty answer tells the asking proxy that this one does not track the player after all. */
    private void answer(UUID id, String to, String player) {
        CultPlayer cultPlayer = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(UUID.fromString(player));
        JsonArray lines = new JsonArray();
        if (cultPlayer != null) {
            for (Component line : CultProfile.render(cultPlayer)) {
                lines.add(GsonComponentSerializer.gson().serializeToTree(line));
            }
        }
        JsonObject response = new JsonObject();
        response.addProperty("type", "response");
        response.addProperty("id", id.toString());
        response.addProperty("to", to);
        response.add("lines", lines);
        publish(response);
    }

    private void publish(JsonObject message) {
        call(send, CHANNEL, message.toString());
    }

    private Object call(Method method, Object... arguments) {
        try {
            return method.invoke(api, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("RedisBungee call " + method.getName() + " failed", failure);
        }
    }

    @Override
    public void close() {
        proxy.getEventManager().unregister(plugin, handler);
        for (var future : pending.values()) future.cancel(false);
    }
}
