package ac.cult.cultac.network.routing;

import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.NetworkRouting.SearchResult;
import ac.cult.cultac.network.PlayerSearchFilters;
import ac.cult.cultac.network.SearchBusyException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Bounded, on-demand searches; the periodic roster carries no profile data. */
final class PlayerSearchNetwork {
    static final String QUERY = "player-search";
    static final String REPLY = "player-search-reply";
    static final int LIMIT = 100;
    private final NetworkService service;
    private final LongSupplier clock;
    private final Budget outbound = new Budget(8);
    private final Budget inbound = new Budget(16);
    private final Budget peers = new Budget(16);
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "CultAC player search");
        thread.setDaemon(true);
        return thread;
    });

    private static final class Budget {
        private final Map<String, Long> senders = new HashMap<>();
        private final int limit;
        private long window;
        private int used;

        Budget(int limit) {
            this.limit = limit;
        }

        synchronized boolean admit(String sender, long now) {
            senders.values().removeIf(until -> until <= now);
            if (now >= window) {
                window = now + 1000;
                used = 0;
            }
            if (used >= limit || senders.containsKey(sender)) return false;
            senders.put(sender, now + 2000);
            used++;
            return true;
        }
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private static final class Pending {
        final Set<String> waiting;
        final List<NetworkPlayer> players = new ArrayList<>();
        final CompletableFuture<SearchResult> result = new CompletableFuture<>();
        int total;

        Pending(Set<String> waiting) {
            this.waiting = new HashSet<>(waiting);
        }

        synchronized void accept(String instance, SearchResult reply) {
            if (result.isDone() || !waiting.remove(instance)) return;
            total += reply.total();
            players.addAll(reply.players());
            if (waiting.isEmpty()) finish();
        }

        synchronized void finish() {
            Map<UUID, NetworkPlayer> unique = new LinkedHashMap<>();
            players.stream()
                    .sorted(Comparator.comparing(NetworkPlayer::instance))
                    .forEach(p -> unique.putIfAbsent(p.uuid(), p));
            result.complete(new SearchResult(
                    List.copyOf(unique.values()), total - (players.size() - unique.size()), waiting.size()));
        }
    }

    PlayerSearchNetwork(NetworkService service, LongSupplier clock) {
        this.service = service;
        this.clock = clock;
        deadlines.setRemoveOnCancelPolicy(true);
        service.on(QUERY, envelope -> {
            if (!service.current()) return;
            JsonObject body = envelope.body();
            String id = body.get("id").getAsString();
            String query = body.get("q").getAsString();
            String sender = UUID.fromString(body.get("sender").getAsString()).toString();
            JsonObject reply;
            if (!inbound.admit(sender, clock.getAsLong()) || !peers.admit(envelope.from(), clock.getAsLong())) {
                reply = new JsonObject();
                reply.addProperty("id", id);
                reply.addProperty("busy", true);
            } else {
                reply = encode(id, local(query));
            }
            service.send(envelope.from(), REPLY, reply);
        });
        service.on(REPLY, envelope -> {
            String id = envelope.body().get("id").getAsString();
            Pending request = pending.get(id);
            if (request != null && envelope.body().has("busy")) {
                request.result.completeExceptionally(new SearchBusyException());
                return;
            }
            if (request != null) request.accept(envelope.from(), decode(envelope.from(), envelope.body()));
        });
    }

    CompletableFuture<SearchResult> search(String query, String sender) {
        PlayerSearchFilters.parse(query);
        String id = UUID.randomUUID().toString();
        Pending request = new Pending(Set.of());
        synchronized (this) {
            if (pending.size() >= 32 || !outbound.admit(sender, clock.getAsLong()))
                return CompletableFuture.failedFuture(new SearchBusyException());
            pending.put(id, request);
            request.result.whenComplete((value, failure) -> pending.remove(id));
        }
        try {
            var deadline = deadlines.schedule(request::finish, 3, TimeUnit.SECONDS);
            request.result.whenComplete((value, failure) -> deadline.cancel(false));
            Set<String> instances = new HashSet<>();
            service.roster().forEach(p -> instances.add(p.instance()));
            instances.add(service.instanceId());
            synchronized (request) {
                if (request.result.isDone()) return request.result;
                request.waiting.addAll(instances);
            }
            JsonObject body = new JsonObject();
            body.addProperty("id", id);
            body.addProperty("q", query);
            body.addProperty("sender", sender);
            request.accept(service.instanceId(), local(query));
            for (String instance : instances) {
                if (!request.result.isDone() && !instance.equals(service.instanceId()))
                    service.send(instance, QUERY, body);
            }
        } catch (Throwable failure) {
            request.result.completeExceptionally(failure);
        }
        return request.result;
    }

    private SearchResult local(String query) {
        var filters = PlayerSearchFilters.parse(query);
        List<NetworkPlayer> matches = service.searchPlayers().stream()
                .filter(p -> filters.stream().allMatch(f -> !f.matched(p).isEmpty()))
                .sorted(Comparator.comparing(p -> p.name().toLowerCase(Locale.ROOT)))
                .toList();
        List<NetworkPlayer> shown = new ArrayList<>();
        for (NetworkPlayer player : matches.stream().limit(LIMIT).toList()) {
            Map<String, List<String>> details = new HashMap<>();
            for (var filter : filters) {
                String value = filter.matched(player).getFirst();
                List<String> values = details.computeIfAbsent(filter.key(), ignored -> new ArrayList<>());
                if (!values.contains(value)) values.add(value);
            }
            details.replaceAll((key, values) -> {
                String value = String.join(", ", values);
                return List.of(value.length() > 64 ? value.substring(0, 61) + "..." : value);
            });
            shown.add(new NetworkPlayer(
                    player.uuid(), player.name(), player.instance(), player.server(), player.brand(), details));
        }
        return new SearchResult(shown, matches.size(), 0);
    }

    static JsonObject encode(String id, SearchResult result) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        body.addProperty("total", result.total());
        JsonArray players = new JsonArray();
        for (NetworkPlayer player : result.players().stream().limit(LIMIT).toList()) {
            JsonArray row = new JsonArray();
            row.add(player.uuid().toString());
            row.add(player.name());
            row.add(player.server());
            row.add(player.brand());
            JsonObject details = new JsonObject();
            player.details().forEach((key, values) -> {
                if (!values.isEmpty()) details.addProperty(key, values.getFirst());
            });
            row.add(details);
            players.add(row);
        }
        body.add("players", players);
        return body;
    }

    static SearchResult decode(String instance, JsonObject body) {
        JsonArray rows = body.getAsJsonArray("players");
        int total = body.get("total").getAsInt();
        if (rows.size() > LIMIT || total < rows.size()) throw new IllegalArgumentException();
        List<NetworkPlayer> players = new ArrayList<>();
        for (var entry : rows) {
            JsonArray row = entry.getAsJsonArray();
            Map<String, List<String>> details = new HashMap<>();
            row.get(4)
                    .getAsJsonObject()
                    .entrySet()
                    .forEach(e -> details.put(e.getKey(), List.of(e.getValue().getAsString())));
            players.add(new NetworkPlayer(
                    UUID.fromString(row.get(0).getAsString()),
                    row.get(1).getAsString(),
                    instance,
                    row.get(2).isJsonNull() ? null : row.get(2).getAsString(),
                    row.get(3).isJsonNull() ? null : row.get(3).getAsString(),
                    details));
        }
        return new SearchResult(players, total, 0);
    }

    void close() {
        pending.values().forEach(Pending::finish);
        pending.clear();
        deadlines.shutdownNow();
    }
}
