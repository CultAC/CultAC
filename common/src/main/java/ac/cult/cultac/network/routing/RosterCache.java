package ac.cult.cultac.network.routing;

import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.routing.NetworkPlatform.OnlinePlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The other instances' players, as their {@value #TYPE} messages list them. A roster body is
 * {@code {servers:{server:{uuid:name}}, brands:{uuid:brand}, full, left:[u], verbose, nonce, o}}, {@code ""} standing
 * for no server: a full one replaces the instance's list, a delta adds or updates players and removes {@code left}.
 * {@code brands} lists only the listed players whose client brand is known; a player it leaves out, or a body without
 * it, has no known brand. A body whose {@code o} is
 * not above the last one applied from its instance is dropped, unless nothing from it was applied for
 * {@value #EXPIRY_MILLIS} ms. An instance is forgotten {@value #EXPIRY_MILLIS} ms after the last message of any type
 * heard from it. Any thread.
 */
final class RosterCache {
    static final String TYPE = "roster";
    static final long EXPIRY_MILLIS = 150_000;
    static final String ORDER = "o";
    static final String NONCE = "nonce";

    private static final class Peer {
        final Map<UUID, NetworkPlayer> players = new LinkedHashMap<>();
        boolean verbose;
        long heard;
        long applied = Long.MIN_VALUE;
        long order = Long.MIN_VALUE;
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
        List<NetworkPlayer> listed = new ArrayList<>();
        if (body.get("servers") instanceof JsonObject servers) {
            for (var server : servers.entrySet()) {
                if (!(server.getValue() instanceof JsonObject players)) continue;
                String name = server.getKey().isEmpty() ? null : server.getKey();
                for (var entry : players.entrySet()) {
                    NetworkPlayer player = player(instance, name, entry.getKey(), entry.getValue(), brands);
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
            peer.verbose = body.has("verbose") && body.get("verbose").getAsBoolean();
        }
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

    /** Whether a live instance has staff with verbose on. */
    boolean verbose(long now) {
        synchronized (peers) {
            for (Peer peer : peers.values()) {
                if (peer.verbose && live(peer, now)) return true;
            }
        }
        return false;
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
            String instance, @Nullable String server, String uuid, JsonElement name, Map<UUID, String> brands) {
        try {
            UUID id = UUID.fromString(uuid);
            return new NetworkPlayer(id, name.getAsString(), instance, server, brands.get(id));
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** The body's {@code brands}, without the entries that are not a UUID naming a string. */
    private static Map<UUID, String> brands(JsonObject body) {
        if (!(body.get("brands") instanceof JsonObject brands)) return Map.of();
        Map<UUID, String> parsed = new HashMap<>();
        for (var entry : brands.entrySet()) {
            if (!(entry.getValue() instanceof JsonPrimitive brand) || !brand.isString()) continue;
            try {
                parsed.put(UUID.fromString(entry.getKey()), brand.getAsString());
            } catch (IllegalArgumentException malformed) {
                // A malformed id names no player.
            }
        }
        return parsed;
    }

    static JsonObject body(
            Collection<OnlinePlayer> players, boolean full, Collection<UUID> left, boolean verbose, String nonce) {
        JsonObject servers = new JsonObject();
        JsonObject brands = new JsonObject();
        for (OnlinePlayer player : players) {
            String server = player.server() == null ? "" : player.server();
            JsonObject listed = servers.getAsJsonObject(server);
            if (listed == null) {
                listed = new JsonObject();
                servers.add(server, listed);
            }
            listed.addProperty(player.uuid().toString(), player.name());
            if (player.brand() != null) brands.addProperty(player.uuid().toString(), player.brand());
        }
        JsonObject body = new JsonObject();
        body.add("servers", servers);
        if (!brands.isEmpty()) body.add("brands", brands);
        body.addProperty("full", full);
        if (!left.isEmpty()) {
            JsonArray gone = new JsonArray();
            for (UUID uuid : left) gone.add(uuid.toString());
            body.add("left", gone);
        }
        body.addProperty("verbose", verbose);
        body.addProperty(NONCE, nonce);
        return body;
    }
}
