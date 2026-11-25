package ac.cult.cultac.network.routing;

import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.routing.NetworkPlatform.OnlinePlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * The other instances' players, as their {@value #TYPE} messages list them. A roster body is
 * {@code {servers:{server:{uuid:name}}, brands:{brand:[uuid]}, shown:{uuid:name}, full, left:[u], verbose,
 * verbose-servers:[server], nonce, o}}, {@code ""} standing for no server and {@code verbose-servers} listing the
 * servers whose players the instance's verbose staff cover, {@value NetworkService#EVERY_SERVER} for all of them;
 * {@code verbose} alone, true, stands for all of them. A full one replaces the instance's list, a delta adds or updates
 * players and removes {@code left}. {@code brands} groups the listed players whose client brand is known by that
 * brand, so a player costs one UUID there however long the brand; a player it leaves out, or a body without it, has no
 * known brand. {@code shown} names each listed player whose own client lists them under a name other than their
 * account; a player it leaves out, or a body without it, has no known shown name. A body too large with {@code brands}
 * goes out without them. A body whose {@code o} is not above the last one applied from its
 * instance is dropped, unless nothing from it was applied for
 * {@value #EXPIRY_MILLIS} ms. An instance is forgotten {@value #EXPIRY_MILLIS} ms after the last message of any type
 * heard from it. Builds that run network commands add {@code role} ({@value #PROXY} or {@value #SERVER}),
 * {@code build} and {@code state} ({@link State}) to every body; a full body without {@code role} comes from an older
 * build, a delta without it keeps what the instance last said, and an instance heard only through other messages has
 * not described itself yet. {@code settings}, the shared settings' records, is {@link NetSettings}'s to read. Any
 * thread.
 */
final class RosterCache {
    static final String TYPE = "roster";
    static final long EXPIRY_MILLIS = 150_000;
    static final String ORDER = "o";
    static final String NONCE = "nonce";
    static final String BRANDS = "brands";
    static final String SHOWN = "shown";
    static final String ROLE = "role";
    static final String BUILD = "build";
    static final String STATE = "state";
    static final String PROXY = "proxy";
    static final String SERVER = "server";

    static final int MAX_LISTED = 512;

    private static final int MAX_BUILD_LENGTH = 200;
    private static final int MAX_SHOWN_LENGTH = 64;
    private static final Pattern BACKEND_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** Whether {@code name} can stand in a roster's backend list and a network command's target. */
    static boolean backendName(String name) {
        return BACKEND_NAME.matcher(name).matches();
    }

    /**
     * A live instance as its roster last described it: {@code older} when that was a full roster without {@code role},
     * from a build without network commands; else a null {@code role} means it has not described itself yet.
     */
    record Instance(
            String id,
            @Nullable String role,
            @Nullable String build,
            @Nullable State state,
            boolean older) {}

    /**
     * An instance's switchable state and player counts: whether it checks players ({@code toggle}), whether it observes
     * only, the stable keys of the checks switched off, the players online, those inspected and those dormant, and the
     * backend servers it answers for: every server registered with a proxy, players or not, and a server's own id. On
     * the wire {@code {toggle, observe, off:[], players, inspected, dormant, backends:[]}}, {@code toggle} and
     * {@code observe} being {@code on} or {@code off}; a field this build does not read is ignored.
     */
    record State(
            boolean checking,
            boolean observe,
            List<String> off,
            int players,
            int inspected,
            int dormant,
            List<String> backends) {
        State {
            off = List.copyOf(off);
            backends = List.copyOf(backends);
        }

        State(boolean checking, boolean observe, List<String> off, int players, int inspected, int dormant) {
            this(checking, observe, off, players, inspected, dormant, List.of());
        }

        State withBackends(List<String> names) {
            return new State(checking, observe, off, players, inspected, dormant, names);
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("toggle", checking ? "on" : "off");
            json.addProperty("observe", observe ? "on" : "off");
            json.add("off", array(off));
            json.addProperty("players", players);
            json.addProperty("inspected", inspected);
            json.addProperty("dormant", dormant);
            json.add("backends", array(backends));
            return json;
        }

        /** The state {@code json} describes, each missing or malformed field at its default; null for a non-object. */
        static @Nullable State parse(@Nullable JsonElement json) {
            if (!(json instanceof JsonObject object)) return null;
            List<String> backends = new ArrayList<>();
            for (String name : texts(object, "backends")) {
                if (backendName(name)) backends.add(name);
            }
            return new State(
                    !"off".equals(text(object, "toggle")),
                    "on".equals(text(object, "observe")),
                    texts(object, "off"),
                    count(object, "players"),
                    count(object, "inspected"),
                    count(object, "dormant"),
                    backends);
        }

        private static JsonArray array(List<String> values) {
            JsonArray array = new JsonArray();
            for (String value : values) array.add(value);
            return array;
        }

        private static List<String> texts(JsonObject object, String key) {
            if (!(object.get(key) instanceof JsonArray values)) return List.of();
            List<String> texts = new ArrayList<>();
            for (JsonElement value : values) {
                if (texts.size() >= MAX_LISTED) break;
                if (value instanceof JsonPrimitive primitive && primitive.isString())
                    texts.add(primitive.getAsString());
            }
            return texts;
        }

        private static int count(JsonObject object, String key) {
            return object.get(key) instanceof JsonPrimitive primitive && primitive.isNumber()
                    ? Math.max(0, primitive.getAsInt())
                    : 0;
        }
    }

    private static final class Peer {
        final Map<UUID, NetworkPlayer> players = new LinkedHashMap<>();
        Set<String> verbose = Set.of();
        long heard;
        long applied = Long.MIN_VALUE;
        long order = Long.MIN_VALUE;

        @Nullable
        String role;

        @Nullable
        String build;

        @Nullable
        State state;

        boolean older;
    }

    /** Guarded by itself. */
    private final Map<String, Peer> peers = new HashMap<>();

    void heard(String instance, long now) {
        synchronized (peers) {
            peers.computeIfAbsent(instance, ignored -> new Peer()).heard = now;
        }
    }

    void apply(String instance, JsonObject body, long now) {
        Long order = order(body);
        boolean full = body.has("full") && body.get("full").getAsBoolean();
        Map<UUID, String> brands = brands(body);
        Map<UUID, String> shown = shown(body);
        boolean described = full || body.has(ROLE);
        String role = role(body);
        String build = text(body, BUILD);
        if (build != null && build.length() > MAX_BUILD_LENGTH) build = build.substring(0, MAX_BUILD_LENGTH);
        State state = State.parse(body.get(STATE));
        List<NetworkPlayer> listed = new ArrayList<>();
        if (body.get("servers") instanceof JsonObject servers) {
            for (var server : servers.entrySet()) {
                if (!(server.getValue() instanceof JsonObject players)) continue;
                String name = server.getKey().isEmpty() ? null : server.getKey();
                for (var entry : players.entrySet()) {
                    NetworkPlayer player = player(instance, name, entry.getKey(), entry.getValue(), brands, shown);
                    if (player != null) listed.add(player);
                }
            }
        }
        synchronized (peers) {
            Peer peer = peers.computeIfAbsent(instance, ignored -> new Peer());
            if (order != null) {
                if (order <= peer.order && now - peer.applied <= EXPIRY_MILLIS) return;
                peer.order = order;
            }
            peer.heard = now;
            peer.applied = now;
            if (full) peer.players.clear();
            if (body.get("left") instanceof JsonArray left) {
                for (JsonElement uuid : left) {
                    try {
                        peer.players.remove(UUID.fromString(uuid.getAsString()));
                    } catch (RuntimeException malformed) {
                        // A malformed id names no player.
                    }
                }
            }
            for (NetworkPlayer player : listed) peer.players.put(player.uuid(), player);
            peer.verbose = verbose(body);
            if (described) {
                peer.role = role;
                peer.build = build;
                peer.state = state;
                peer.older = !body.has(ROLE);
            }
        }
    }

    /** Every live instance, by id. */
    List<Instance> instances(long now) {
        List<Instance> instances = new ArrayList<>();
        synchronized (peers) {
            for (Map.Entry<String, Peer> entry : peers.entrySet()) {
                Peer peer = entry.getValue();
                if (live(peer, now))
                    instances.add(new Instance(entry.getKey(), peer.role, peer.build, peer.state, peer.older));
            }
        }
        instances.sort(Comparator.comparing(Instance::id));
        return instances;
    }

    private static @Nullable String role(JsonObject body) {
        String role = text(body, ROLE);
        return PROXY.equals(role) || SERVER.equals(role) ? role : null;
    }

    private static @Nullable String text(JsonObject object, String key) {
        return object.get(key) instanceof JsonPrimitive primitive && primitive.isString()
                ? primitive.getAsString()
                : null;
    }

    List<NetworkPlayer> players(long now) {
        List<NetworkPlayer> players = new ArrayList<>();
        synchronized (peers) {
            for (Peer peer : peers.values()) {
                if (live(peer, now)) players.addAll(peer.players.values());
            }
        }
        return players;
    }

    /** When {@code instance} was last heard from, or {@link Long#MIN_VALUE} when it is forgotten. */
    long heardAt(String instance, long now) {
        synchronized (peers) {
            Peer peer = peers.get(instance);
            return peer != null && live(peer, now) ? peer.heard : Long.MIN_VALUE;
        }
    }

    /** The servers whose players the verbose staff on live instances cover. */
    Set<String> verbose(long now) {
        Set<String> servers = new HashSet<>();
        synchronized (peers) {
            for (Peer peer : peers.values()) {
                if (live(peer, now)) servers.addAll(peer.verbose);
            }
        }
        return servers;
    }

    private static Set<String> verbose(JsonObject body) {
        if (body.get("verbose-servers") instanceof JsonArray listed) {
            Set<String> servers = new HashSet<>();
            for (JsonElement server : listed) {
                if (server.isJsonPrimitive()) servers.add(server.getAsString());
            }
            return servers;
        }
        boolean any = body.get("verbose") instanceof JsonPrimitive flag && flag.isBoolean() && flag.getAsBoolean();
        return any ? Set.of(NetworkService.EVERY_SERVER) : Set.of();
    }

    void expire(long now) {
        synchronized (peers) {
            peers.values().removeIf(peer -> !live(peer, now));
        }
    }

    private static boolean live(Peer peer, long now) {
        return now - peer.heard <= EXPIRY_MILLIS;
    }

    private static @Nullable Long order(JsonObject body) {
        try {
            return body.has(ORDER) ? body.get(ORDER).getAsLong() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static @Nullable NetworkPlayer player(
            String instance,
            @Nullable String server,
            String uuid,
            JsonElement name,
            Map<UUID, String> brands,
            Map<UUID, String> shown) {
        try {
            UUID id = UUID.fromString(uuid);
            return new NetworkPlayer(id, name.getAsString(), instance, server, brands.get(id), Map.of(), shown.get(id));
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** Each player's shown name from the body's {@code shown}, without the entries that are not a short name. */
    private static Map<UUID, String> shown(JsonObject body) {
        if (!(body.get(SHOWN) instanceof JsonObject shown)) return Map.of();
        Map<UUID, String> parsed = new HashMap<>();
        for (var entry : shown.entrySet()) {
            if (!(entry.getValue() instanceof JsonPrimitive name) || !name.isString()) continue;
            String text = name.getAsString();
            if (text.isEmpty() || text.length() > MAX_SHOWN_LENGTH) continue;
            try {
                parsed.put(UUID.fromString(entry.getKey()), text);
            } catch (IllegalArgumentException malformed) {
                // A malformed id names no player.
            }
        }
        return parsed;
    }

    /** Each player's brand from the body's {@code brands}, without the entries that are not a list of UUIDs. */
    private static Map<UUID, String> brands(JsonObject body) {
        if (!(body.get(BRANDS) instanceof JsonObject brands)) return Map.of();
        Map<UUID, String> parsed = new HashMap<>();
        for (var entry : brands.entrySet()) {
            if (!(entry.getValue() instanceof JsonArray players)) continue;
            for (JsonElement player : players) {
                if (!(player instanceof JsonPrimitive id) || !id.isString()) continue;
                try {
                    parsed.put(UUID.fromString(id.getAsString()), entry.getKey());
                } catch (IllegalArgumentException malformed) {
                    // A malformed id names no player.
                }
            }
        }
        return parsed;
    }

    static JsonObject body(
            Collection<OnlinePlayer> players,
            boolean full,
            Collection<UUID> left,
            Set<String> verbose,
            String nonce) {
        JsonObject servers = new JsonObject();
        JsonObject brands = new JsonObject();
        JsonObject shown = new JsonObject();
        for (OnlinePlayer player : players) {
            String server = player.server() == null ? "" : player.server();
            JsonObject listed = servers.getAsJsonObject(server);
            if (listed == null) {
                listed = new JsonObject();
                servers.add(server, listed);
            }
            listed.addProperty(player.uuid().toString(), player.name());
            if (player.brand() != null) {
                JsonArray branded = brands.getAsJsonArray(player.brand());
                if (branded == null) {
                    branded = new JsonArray();
                    brands.add(player.brand(), branded);
                }
                branded.add(player.uuid().toString());
            }
            if (player.shown() != null) shown.addProperty(player.uuid().toString(), player.shown());
        }
        JsonObject body = new JsonObject();
        body.add("servers", servers);
        if (!brands.isEmpty()) body.add(BRANDS, brands);
        if (!shown.isEmpty()) body.add(SHOWN, shown);
        body.addProperty("full", full);
        if (!left.isEmpty()) {
            JsonArray gone = new JsonArray();
            for (UUID uuid : left) gone.add(uuid.toString());
            body.add("left", gone);
        }
        body.addProperty("verbose", !verbose.isEmpty());
        JsonArray wanted = new JsonArray();
        for (String server : verbose) wanted.add(server);
        body.add("verbose-servers", wanted);
        body.addProperty(NONCE, nonce);
        return body;
    }
}
