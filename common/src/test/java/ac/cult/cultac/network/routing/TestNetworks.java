package ac.cult.cultac.network.routing;

import ac.cult.cultac.utils.anticheat.LogThrottle;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.Nullable;

/** Started network services on one in-memory broker, each running every message on the caller's thread. */
public final class TestNetworks {
    private static final byte[] KEY = "a shared network secret of some length".getBytes(StandardCharsets.UTF_8);
    private static final NetworkSettings SETTINGS =
            new NetworkSettings("redis", "", "default", "", "", List.of(), "", "", "");
    private static final NetworkPlatform NO_PLAYERS = new NetworkPlatform() {
        @Override
        public Collection<OnlinePlayer> onlinePlayers() {
            return List.of();
        }

        @Override
        public String address() {
            return "test:1";
        }
    };

    private final FakeBroker broker = new FakeBroker();
    private final AtomicLong clock = new AtomicLong(1_793_140_000_000L);

    public NetworkService started(String instance) {
        return start(instance, broker.transport());
    }

    /** A service with no transport: only the in-process bus. */
    public NetworkService withoutTransport(String instance) {
        return start(instance, null);
    }

    private NetworkService start(String instance, @Nullable MessageTransport transport) {
        NetworkService service = new NetworkService(
                SETTINGS, NO_PLAYERS, null, instance, KEY, transport, clock::get, null, null, new LogThrottle());
        service.start();
        return service;
    }

    /** Runs {@code service}'s periodic roster tick now. */
    public void tick(NetworkService service) {
        service.tick();
    }

    /** The body of every {@code type} message broadcast so far, oldest first. */
    public List<JsonObject> broadcasts(String type) {
        List<JsonObject> bodies = new ArrayList<>();
        for (FakeBroker.Published message : broker.on(SETTINGS.channel(NetworkSettings.ALL))) {
            String text = message.text();
            JsonObject envelope = JsonParser.parseString(text.substring(text.indexOf('.') + 1))
                    .getAsJsonObject();
            if (envelope.get("type").getAsString().equals(type)) bodies.add(envelope.getAsJsonObject("body"));
        }
        return bodies;
    }
}
