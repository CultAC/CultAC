package ac.cult.cultac.manager.alerts;

import ac.cult.cultac.network.routing.Envelope;
import ac.cult.cultac.network.routing.MessageHandler.Origin;
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
 * Carries alerts between instances while a network transport runs. Each alert, verbose line and brand message raised
 * here is broadcast as an {@value #TYPE} message {@code {kind, player:{u,n}, server, component, pair?}}, where
 * {@code component} is adventure JSON, {@code player} is absent for an alert about no player, and {@code pair} is the
 * id a verbose line shares with the alert it precedes. Verbose goes out only while a peer's roster flags
 * {@value #DEMAND_FLAG}, which this instance raises while one of its staff takes verbose with global scope. Each
 * received message reaches {@link Staff} through the one engine generation here that claims it.
 */
public final class AlertNetwork {
    public static final String TYPE = "alert";
    public static final String DEMAND_FLAG = "gv";
    /** The host-shared key of the alert envelope ids already taken by a generation here, with when. */
    public static final String CLAIMS = "cultac.network.claims.alert";
    /** Same-instance only: every generation republishes its demand flag. */
    static final String DEMAND_CHANGED = "alert.demand";

    private static final long DEMAND_CHECK_MILLIS = 1_000;

    /** This instance's staff, as the network sees them. */
    public interface Staff {
        /**
         * Shows an alert another instance raised; never to the console.
         *
         * @param server the subject's server; null when unknown or the alert is about no player
         * @param pair the id this message shares with the other of its verbose and alert pair
         */
        void received(AlertKind kind, Component component, @Nullable String server, @Nullable String pair);

        /** Whether a staff member here has verbose on with global scope. */
        boolean wantGlobalVerbose();
    }

    private final Supplier<@Nullable NetworkService> service;
    private final Staff staff;
    private final SharedClaims claims;
    private final LongSupplier clock;

    private volatile long nextDemandCheck = Long.MIN_VALUE;
    private volatile boolean peersWantVerbose;

    /**
     * @param service this generation's running network service
     * @param claims the claims every engine generation on this instance shares
     */
    public AlertNetwork(
            Supplier<@Nullable NetworkService> service, Staff staff, SharedClaims claims, LongSupplier clock) {
        this.service = service;
        this.staff = staff;
        this.claims = claims;
        this.clock = clock;
    }

    /**
     * Takes {@code started}'s alert messages and sets its demand flag, again before each roster snapshot; for every
     * service, before it subscribes.
     */
    public void attach(NetworkService started) {
        started.on(TYPE, this::receive);
        started.on(DEMAND_CHANGED, (envelope, origin) -> publishDemand(started));
        started.beforeSnapshot(() -> publishDemand(started));
        nextDemandCheck = Long.MIN_VALUE;
        publishDemand(started);
    }

    /** Whether a transport carries alerts to the other instances. */
    public boolean running() {
        NetworkService running = service.get();
        return running != null && running.enabled();
    }

    /** This instance's id on the network while a transport runs; null otherwise. */
    public @Nullable String instanceId() {
        NetworkService running = service.get();
        return running != null && running.enabled() ? running.instanceId() : null;
    }

    /** A new id for a verbose line and the alert it precedes. */
    public static String newPair() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return new UUID(random.nextLong(), random.nextLong()).toString();
    }

    /** Whether a peer's staff take verbose network-wide; re-read at most once a second. */
    public boolean verboseWanted() {
        NetworkService running = service.get();
        if (running == null || !running.enabled()) return false;
        long now = clock.getAsLong();
        if (now < nextDemandCheck) return peersWantVerbose;
        boolean wanted = false;
        for (JsonObject flags : running.peerFlags().values()) {
            if (flags.get(DEMAND_FLAG) instanceof JsonPrimitive flag && flag.isBoolean() && flag.getAsBoolean()) {
                wanted = true;
                break;
            }
        }
        peersWantVerbose = wanted;
        nextDemandCheck = now + DEMAND_CHECK_MILLIS;
        return wanted;
    }

    /**
     * Broadcasts a message raised here; verbose only while {@link #verboseWanted}. False when it was not sent.
     *
     * @param pair for a verbose line, a {@link #newPair}; for an alert, the pair its verbose line was broadcast with
     */
    public boolean publish(AlertKind kind, AlertSubject subject, Component component, @Nullable String pair) {
        NetworkService running = service.get();
        if (running == null || !running.enabled()) return false;
        if (kind == AlertKind.VERBOSE && !verboseWanted()) return false;
        return running.broadcast(TYPE, body(kind, subject, component, pair));
    }

    /** Has every engine generation here republish its demand flag; only the current one's reaches the roster. */
    public void demandChanged() {
        NetworkService running = service.get();
        if (running != null && !running.sendLocal(DEMAND_CHANGED, new JsonObject())) publishDemand(running);
    }

    private void publishDemand(NetworkService running) {
        running.flag(DEMAND_FLAG, new JsonPrimitive(staff.wantGlobalVerbose()));
    }

    void receive(Envelope envelope, Origin origin) {
        if (origin == Origin.LOCAL) return;
        JsonObject body = envelope.body();
        AlertKind kind = AlertKind.parse(string(body, "kind"));
        JsonElement component = body.get("component");
        if (kind == null || component == null || component.isJsonNull()) return;
        if (!claims.claim(envelope.id().toString())) return;
        staff.received(
                kind,
                GsonComponentSerializer.gson().deserialize(component.toString()),
                body.has("player") ? string(body, "server") : null,
                string(body, "pair"));
    }

    static JsonObject body(AlertKind kind, AlertSubject subject, Component component, @Nullable String pair) {
        JsonObject body = new JsonObject();
        body.addProperty("kind", kind.id());
        if (subject.uuid() != null) {
            JsonObject player = new JsonObject();
            player.addProperty("u", subject.uuid().toString());
            player.addProperty("n", subject.name());
            body.add("player", player);
        }
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
