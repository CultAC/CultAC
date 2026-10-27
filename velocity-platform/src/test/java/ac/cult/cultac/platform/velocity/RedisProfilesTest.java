package ac.cult.cultac.platform.velocity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.command.profile.RemoteProfiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.imaginarycode.minecraft.redisbungee.AbstractRedisBungeeAPI;
import com.imaginarycode.minecraft.redisbungee.events.PubSubMessageEvent;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RedisProfilesTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final AbstractRedisBungeeAPI api = new AbstractRedisBungeeAPI();
    private final ProxyServer proxy = mock(ProxyServer.class);
    private final EventManager events = mock(EventManager.class);
    private final Object plugin = new Object();
    private RedisProfiles profiles;

    @BeforeEach
    void setUp() {
        AbstractRedisBungeeAPI.instance = api;
        PluginManager plugins = mock(PluginManager.class);
        PluginContainer container = mock(PluginContainer.class);
        doReturn(Optional.of(api)).when(container).getInstance();
        when(plugins.getPlugin("redisbungee")).thenReturn(Optional.of(container));
        when(proxy.getPluginManager()).thenReturn(plugins);
        when(proxy.getEventManager()).thenReturn(events);
    }

    @AfterEach
    void tearDown() {
        AbstractRedisBungeeAPI.instance = null;
    }

    @Test
    void runtimeThatMayNotReportAnUntrackedPlayerStaysSilent() throws Exception {
        var handler = start(uuid -> false);

        handler.execute(event(request("proxy-b", "proxy-a")));

        assertTrue(api.published.isEmpty());
    }

    @Test
    void runtimeThatMayReportAnUntrackedPlayerAnswersEmpty() throws Exception {
        var handler = start(uuid -> true);

        handler.execute(event(request("proxy-b", "proxy-a")));

        assertEquals(1, api.published.size());
        JsonObject answer = JsonParser.parseString(api.published.getFirst()).getAsJsonObject();
        assertEquals("response", answer.get("type").getAsString());
        assertEquals("proxy-b", answer.get("to").getAsString());
        assertTrue(answer.getAsJsonArray("lines").isEmpty());
    }

    @Test
    void profileArrivingAfterAnEmptyAnswerWins() throws Exception {
        var handler = start(uuid -> true);
        var request = profiles.request(new RemoteProfiles.Remote(PLAYER, "Target", "proxy-b", null));
        String id = sentRequestId();

        handler.execute(event(response(id, List.of())));
        assertFalse(request.isDone());
        handler.execute(event(response(id, List.of(Component.text("profile line")))));

        assertEquals(List.of(Component.text("profile line")), request.get(1, TimeUnit.SECONDS));
    }

    @Test
    void emptyAnswerCompletesAfterTheGraceWindow() throws Exception {
        var handler = start(uuid -> true);
        var request = profiles.request(new RemoteProfiles.Remote(PLAYER, "Target", "proxy-b", null));

        handler.execute(event(response(sentRequestId(), List.of())));

        assertEquals(List.of(), request.get(RedisProfiles.EMPTY_ANSWER_GRACE_MILLIS + 2_000, TimeUnit.MILLISECONDS));
    }

    @SuppressWarnings("unchecked")
    private EventHandler<Object> start(Predicate<UUID> reportsUntracked) {
        profiles = RedisProfiles.create(proxy, plugin, Logger.getAnonymousLogger(), reportsUntracked);
        assertNotNull(profiles);
        ArgumentCaptor<EventHandler<Object>> handler = ArgumentCaptor.forClass(EventHandler.class);
        verify(events)
                .register(same(plugin), eq((Class<Object>) (Class<?>) PubSubMessageEvent.class), handler.capture());
        return handler.getValue();
    }

    private String sentRequestId() {
        return JsonParser.parseString(api.published.getLast())
                .getAsJsonObject()
                .get("id")
                .getAsString();
    }

    private static PubSubMessageEvent event(JsonObject message) {
        return new PubSubMessageEvent(RedisProfiles.CHANNEL, message.toString());
    }

    private static JsonObject request(String from, String to) {
        JsonObject request = new JsonObject();
        request.addProperty("type", "request");
        request.addProperty("id", UUID.randomUUID().toString());
        request.addProperty("from", from);
        request.addProperty("to", to);
        request.addProperty("player", PLAYER.toString());
        return request;
    }

    private static JsonObject response(String id, List<Component> lines) {
        JsonArray array = new JsonArray();
        for (Component line : lines) array.add(GsonComponentSerializer.gson().serializeToTree(line));
        JsonObject response = new JsonObject();
        response.addProperty("type", "response");
        response.addProperty("id", id);
        response.addProperty("to", "proxy-a");
        response.add("lines", array);
        return response;
    }
}
