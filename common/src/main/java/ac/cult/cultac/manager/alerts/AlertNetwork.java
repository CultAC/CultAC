package ac.cult.cultac.manager.alerts;

import ac.cult.cultac.network.routing.Envelope;
import ac.cult.cultac.network.routing.NetworkService;
import ac.cult.cultac.network.routing.SharedClaims;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jetbrains.annotations.Nullable;

/**
 * Carries alerts between instances while a network transport runs, each as an {@value #TYPE} message
 * {@code {kind, player, server, component, pair?}}: {@code player} is false for an alert about no player,
 * {@code component} is adventure JSON and {@code pair} the id a verbose line shares with its alert. Verbose goes out
 * only while a peer's roster says its staff take verbose. Each received message reaches {@link Staff} through the one
 * engine generation here that claims it.
 */
public final class AlertNetwork {
    public static final String TYPE = "alert";
    /** The {@link SharedClaims} scope of the alert envelope ids already taken by a generation here. */
    public static final String CLAIMS = "alert";
    /** Same-instance only: the current generation republishes whether staff here take verbose. */
    static final String DEMAND_CHANGED = "alert.demand";

    private static final long DEMAND_CHECK_MILLIS = 1_000;

    /** This instance's staff, as the network sees them. */
    public interface Staff {
        /** Shows an alert another instance raised, about a player on {@code server} if known; never to the console. */
        void received(AlertKind kind, Component component, @Nullable String server, @Nullable String pair);

        /** Whether a staff member here has verbose on, whatever their scope. */
        boolean wantVerbose();
    }

    private final Supplier<@Nullable NetworkService> service;
    private final Staff staff;
    private final SharedClaims claims;
    private final LongSupplier clock;

    private volatile long nextDemandCheck = Long.MIN_VALUE;
    private volatile boolean peersWantVerbose;

    /** {@code claims} are shared by every engine generation on this instance. */
    public AlertNetwork(
            Supplier<@Nullable NetworkService> service, Staff staff, SharedClaims claims, LongSupplier clock) {
        this.service = service;
        this.staff = staff;
        this.claims = claims;
        this.clock = clock;
    }

    /** Takes {@code started}'s alert messages and has its roster say whether staff here take verbose. */
    public void attach(NetworkService started) {
        started.on(TYPE, this::receive);
        started.on(DEMAND_CHANGED, envelope -> started.verboseChanged());
        started.verbose(staff::wantVerbose);
        nextDemandCheck = Long.MIN_VALUE;
    }

    public boolean running() {
        NetworkService running = service.get();
        return running != null && running.enabled();
    }

    /** This instance's id on the network while a transport runs; null otherwise. */
    public @Nullable String instanceId() {
        NetworkService running = service.get();
        return running != null && running.enabled() ? running.instanceId() : null;
    }

    public static String newPair() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return new UUID(random.nextLong(), random.nextLong()).toString();
    }

    /** Whether a peer's staff take verbose; re-read at most once a second. */
    public boolean verboseWanted() {
        NetworkService running = service.get();
        if (running == null || !running.enabled()) return false;
        long now = clock.getAsLong();
        if (now < nextDemandCheck) return peersWantVerbose;
        boolean wanted = running.peersWantVerbose();
        peersWantVerbose = wanted;
        nextDemandCheck = now + DEMAND_CHECK_MILLIS;
        return wanted;
    }

    /** Broadcasts a message raised here, verbose only while {@link #verboseWanted}; false when it was not sent. */
    public boolean publish(AlertKind kind, AlertSubject subject, Component component, @Nullable String pair) {
        NetworkService running = service.get();
        if (running == null || !running.enabled()) return false;
        if (kind == AlertKind.VERBOSE && !verboseWanted()) return false;
        return running.broadcast(TYPE, body(kind, subject, component, pair));
    }

    public void demandChanged() {
        NetworkService running = service.get();
        if (running != null && !running.sendLocal(DEMAND_CHANGED, new JsonObject())) running.verboseChanged();
    }

    void receive(Envelope envelope) {
        JsonObject body = envelope.body();
        AlertKind kind = AlertKind.parse(string(body, "kind"));
        JsonElement component = body.get("component");
        if (kind == null || component == null || component.isJsonNull()) return;
        if (!claims.claim(envelope.id().toString())) return;
        boolean player = body.get("player") instanceof JsonPrimitive flag && flag.isBoolean() && flag.getAsBoolean();
        staff.received(
                kind,
                GsonComponentSerializer.gson().deserialize(component.toString()),
                player ? string(body, "server") : null,
                string(body, "pair"));
    }

    static JsonObject body(AlertKind kind, AlertSubject subject, Component component, @Nullable String pair) {
        JsonObject body = new JsonObject();
        body.addProperty("kind", kind.id());
        body.addProperty("player", !subject.none());
        if (subject.server() == null) body.add("server", JsonNull.INSTANCE);
        else body.addProperty("server", subject.server());
        // Crosses as text: the serializer belongs to the platform, whose JSON types are not this runtime's.
        body.add(
                "component",
                JsonParser.parseString(GsonComponentSerializer.gson().serialize(component)));
        if (pair != null) body.addProperty("pair", pair);
        return body;
    }

    private static @Nullable String string(JsonObject body, String key) {
        return body.get(key) instanceof JsonPrimitive value && value.isString() ? value.getAsString() : null;
    }
}
