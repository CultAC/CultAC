package ac.cult.cultac.network.routing;

import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.routing.NetworkPlatform.OnlinePlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
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
 * {@code {players:[{u,n,s}], full, left:[u], flags:{}}}: a full one replaces the instance's list, a delta adds or
 * updates {@code players} and removes {@code left}. An instance is forgotten {@value #EXPIRY_MILLIS} ms after the last
 * message of any type heard from it. Any thread.
 */
final class RosterCache {
    static final String TYPE = "roster";
    static final long EXPIRY_MILLIS = 45_000;

    private static final class Peer {
        final Map<UUID, NetworkPlayer> players = new LinkedHashMap<>();
        JsonObject flags = new JsonObject();
        long heard;
    }

    /** Guarded by itself. */
    private final Map<String, Peer> peers = new HashMap<>();

    /** Notes a message from {@code instance} at {@code now}. */
    void heard(String instance, long now) {
        synchronized (peers) {
            peers.computeIfAbsent(instance, ignored -> new Peer()).heard = now;
        }
    }

    /** Applies a roster body from {@code instance}, heard at {@code now}. */
    void apply(String instance, JsonObject body, long now) {
        boolean full = body.has("full") && body.get("full").getAsBoolean();
        List<NetworkPlayer> listed = new ArrayList<>();
        if (body.get("players") instanceof JsonArray players) {
            for (JsonElement entry : players) {
                NetworkPlayer player = player(instance, entry);
                if (player != null) listed.add(player);
            }
        }
        synchronized (peers) {
            Peer peer = peers.computeIfAbsent(instance, ignored -> new Peer());
            peer.heard = now;
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
            if (body.get("flags") instanceof JsonObject flags) peer.flags = flags.deepCopy();
        }
    }

    /** Every player of every instance heard from within {@value #EXPIRY_MILLIS} ms of {@code now}. */
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

    /** Each live instance's roster flags. */
    Map<String, JsonObject> flags(long now) {
        Map<String, JsonObject> flags = new HashMap<>();
        synchronized (peers) {
            for (var entry : peers.entrySet()) {
                if (live(entry.getValue(), now))
                    flags.put(entry.getKey(), entry.getValue().flags.deepCopy());
            }
        }
        return flags;
    }

    /** Forgets every instance not heard from within {@value #EXPIRY_MILLIS} ms of {@code now}. */
    void expire(long now) {
        synchronized (peers) {
            peers.values().removeIf(peer -> !live(peer, now));
        }
    }

    private static boolean live(Peer peer, long now) {
        return now - peer.heard <= EXPIRY_MILLIS;
    }

    private static @Nullable NetworkPlayer player(String instance, JsonElement entry) {
        try {
            JsonObject player = entry.getAsJsonObject();
            JsonElement server = player.get("s");
            return new NetworkPlayer(
                    UUID.fromString(player.get("u").getAsString()),
                    player.get("n").getAsString(),
                    instance,
                    server == null || server.isJsonNull() ? null : server.getAsString());
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** A roster body listing {@code players}, a full snapshot or a delta that also drops {@code left}. */
    static JsonObject body(Collection<OnlinePlayer> players, boolean full, Collection<UUID> left, JsonObject flags) {
        JsonArray listed = new JsonArray();
        for (OnlinePlayer player : players) {
            JsonObject entry = new JsonObject();
            entry.addProperty("u", player.uuid().toString());
            entry.addProperty("n", player.name());
            if (player.server() == null) entry.add("s", JsonNull.INSTANCE);
            else entry.addProperty("s", player.server());
            listed.add(entry);
        }
        JsonObject body = new JsonObject();
        body.add("players", listed);
        body.addProperty("full", full);
        if (!left.isEmpty()) {
            JsonArray gone = new JsonArray();
            for (UUID uuid : left) gone.add(uuid.toString());
            body.add("left", gone);
        }
        body.add("flags", flags);
        return body;
    }
}
