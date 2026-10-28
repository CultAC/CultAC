package ac.cult.cultac.cloud.bans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.CloudSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StaffBansTest {
    private static final String KEY = "intake-secret-123";
    private static final UUID PLAYER = UUID.fromString("6a1f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6b");
    private static final UUID OTHER = UUID.fromString("7b2f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6c");
    private static final String BAN = "0f0e0d0c-0b0a-4908-8706-050403020100";
    private static final String TIMED = "1f0e0d0c-0b0a-4908-8706-050403020101";
    private static final long NOW = Instant.parse("2026-10-27T12:00:00Z").toEpochMilli();

    private record Seen(String method, String uri, String authorization, String body) {}

    private HttpServer server;
    private final ExecutorService calls = Executors.newSingleThreadExecutor(task -> new Thread(task, "ban-call"));
    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    private volatile JsonArray pending = new JsonArray();
    private volatile int pendingStatus = 200;
    private volatile int claimStatus = 200;

    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private final List<String> dispatched = Collections.synchronizedList(new ArrayList<>());
    private volatile RuntimeException dispatchFailure;
    private final AtomicLong clock = new AtomicLong(NOW);
    private final AtomicLong chains = new AtomicLong();
    private final StaffBans.Handled handled = new StaffBans.Handled(StaffBans.MAX_HANDLED);

    private final StaffBans.Host host = new StaffBans.Host() {
        @Override
        public boolean online(UUID player) {
            return online.contains(player);
        }

        @Override
        public CompletableFuture<Void> dispatch(String command, UUID player) {
            if (dispatchFailure != null) throw dispatchFailure;
            dispatched.add(command);
            return CompletableFuture.completedFuture(null);
        }
    };

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();
    }

    @AfterEach
    void stop() {
        chains.incrementAndGet();
        StaffBans.stop();
        server.stop(0);
        calls.shutdownNow();
    }

    private void answer(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String uri = exchange.getRequestURI().toString();
        seen.add(new Seen(
                exchange.getRequestMethod(), uri, exchange.getRequestHeaders().getFirst("Authorization"), body));
        int status;
        String reply;
        if (uri.startsWith("/api/v1/bans/pending")) {
            status = pendingStatus;
            JsonObject answer = new JsonObject();
            answer.addProperty("pollSeconds", 1);
            answer.addProperty("graceSeconds", 15);
            answer.add("bans", pending);
            reply = answer.toString();
        } else if (uri.endsWith("/claim")) {
            status = claimStatus;
            reply = "{\"leaseSeconds\":60}";
        } else {
            status = 200;
            reply = "{}";
        }
        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private StaffBans bans(String serverId, StaffBans.Commands commands) {
        return new StaffBans(
                base(),
                KEY,
                serverId,
                commands,
                HttpClient.newHttpClient(),
                calls,
                host,
                clock::get,
                chains,
                handled,
                seconds -> CompletableFuture.delayedExecutor(seconds * 10, TimeUnit.MILLISECONDS));
    }

    private StaffBans bans() {
        return bans("arena-1", new StaffBans.Commands("ban %player% %reason%", "tempban %player% %duration% %reason%"));
    }

    private static JsonObject ban(String id, UUID player, Long seconds, long createdAt) {
        JsonObject ban = new JsonObject();
        ban.addProperty("id", id);
        ban.addProperty("player", player.toString());
        ban.addProperty("name", player.equals(PLAYER) ? "Notch" : "Other");
        ban.addProperty("reason", "fly hacks");
        ban.addProperty("cheat", "fly");
        ban.addProperty("cheatName", "Fly");
        ban.addProperty("durationSeconds", seconds);
        ban.addProperty("staff", "Mod");
        ban.addProperty("createdAt", Instant.ofEpochMilli(createdAt).toString());
        ban.addProperty("link", "https://panel.example/players/" + player);
        return ban;
    }

    private void pending(JsonObject... bans) {
        JsonArray array = new JsonArray();
        for (JsonObject ban : bans) array.add(ban);
        pending = array;
    }

    private List<Seen> posts() {
        synchronized (seen) {
            return seen.stream().filter(s -> s.method().equals("POST")).toList();
        }
    }

    private long gets(String serverId) {
        synchronized (seen) {
            return seen.stream()
                    .filter(s -> s.uri().equals("/api/v1/bans/pending?server=" + serverId))
                    .count();
        }
    }

    @Test
    void aPendingBanIsClaimedDispatchedAndReported() throws Exception {
        pending(ban(BAN, PLAYER, null, NOW - 60_000));

        assertEquals(StaffBans.MIN_POLL_SECONDS, bans().pollOnce().get(5, TimeUnit.SECONDS));

        assertEquals(List.of("ban Notch fly hacks"), dispatched);
        Seen get = seen.get(0);
        assertEquals("GET", get.method());
        assertEquals("/api/v1/bans/pending?server=arena-1", get.uri());
        assertEquals("Bearer " + KEY, get.authorization());
        List<Seen> posts = posts();
        assertEquals(2, posts.size());
        assertEquals("/api/v1/bans/" + BAN + "/claim", posts.get(0).uri());
        assertEquals(
                JsonParser.parseString("{\"server\":\"arena-1\"}"),
                JsonParser.parseString(posts.get(0).body()));
        assertEquals("/api/v1/bans/" + BAN + "/result", posts.get(1).uri());
        assertEquals(
                JsonParser.parseString("{\"server\":\"arena-1\",\"ok\":true,\"message\":\"dispatched\"}"),
                JsonParser.parseString(posts.get(1).body()));
    }

    @Test
    void anOnlinePlayerIsClaimedAtOnceAndAnOfflineOneAfterTheGrace() throws Exception {
        online.add(PLAYER);
        pending(ban(BAN, PLAYER, null, NOW), ban(TIMED, OTHER, 3600L, NOW));
        StaffBans bans = bans();

        bans.pollOnce().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("ban Notch fly hacks"), dispatched);

        clock.set(NOW + 14_000);
        bans.pollOnce().get(5, TimeUnit.SECONDS);
        assertEquals(1, dispatched.size());

        clock.set(NOW + 15_000);
        bans.pollOnce().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("ban Notch fly hacks", "tempban Other 1h fly hacks"), dispatched);
    }

    @Test
    void anEmptyTempCommandNeverClaimsATimedBan() throws Exception {
        pending(ban(TIMED, PLAYER, 3600L, NOW - 60_000));

        bans("arena-1", new StaffBans.Commands("ban %player%", "")).pollOnce().get(5, TimeUnit.SECONDS);

        assertTrue(posts().isEmpty());
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void aLostClaimDispatchesNothing() throws Exception {
        claimStatus = 409;
        pending(ban(BAN, PLAYER, null, NOW - 60_000));

        bans().pollOnce().get(5, TimeUnit.SECONDS);

        assertEquals(1, posts().size());
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void aDispatchThatThrowsIsReportedAsFailed() throws Exception {
        dispatchFailure = new IllegalStateException("Unknown command ban");
        pending(ban(BAN, PLAYER, null, NOW - 60_000));

        bans().pollOnce().get(5, TimeUnit.SECONDS);

        Seen result = posts().get(1);
        assertEquals("/api/v1/bans/" + BAN + "/result", result.uri());
        assertEquals(
                JsonParser.parseString("{\"server\":\"arena-1\",\"ok\":false,\"message\":\"Unknown command ban\"}"),
                JsonParser.parseString(result.body()));
    }

    @Test
    void aBanIsNeverDispatchedTwiceAcrossRoundsAndReloads() throws Exception {
        pending(ban(BAN, PLAYER, null, NOW - 60_000));

        bans().pollOnce().get(5, TimeUnit.SECONDS);
        bans().pollOnce().get(5, TimeUnit.SECONDS);
        StaffBans reloaded = bans();
        reloaded.pollOnce().get(5, TimeUnit.SECONDS);

        assertEquals(1, dispatched.size());
        assertEquals(2, posts().size());
    }

    @Test
    void aServiceWithoutStaffBansIsAskedEveryFiveMinutes() throws Exception {
        pendingStatus = 404;

        assertEquals(StaffBans.ABSENT_POLL_SECONDS, bans().pollOnce().get(5, TimeUnit.SECONDS));
    }

    @Test
    void aReconfigureStopsTheOldChain() throws Exception {
        StaffBans.Commands commands = new StaffBans.Commands("ban %player%", "");
        bans("old", commands).start();
        waitFor(() -> gets("old") >= 3);
        bans("new", commands).start();
        waitFor(() -> gets("new") >= 3);
        long old = gets("old");

        Thread.sleep(200);

        assertEquals(old, gets("old"));
        assertTrue(gets("new") > 3);
    }

    @Test
    void noCommandSetSendsNothing() throws Exception {
        CloudSettings settings = new CloudSettings(base(), KEY, "{server}", "arena-1", List.of(5), false, false);

        StaffBans.configure(settings, StaffBans.Commands.NONE, "arena-1");
        StaffBans.configure(settings, new StaffBans.Commands("ban %player%", ""), null);
        Thread.sleep(200);

        assertTrue(seen.isEmpty());
        assertFalse(StaffBans.Commands.NONE.any());
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out");
            Thread.sleep(10);
        }
    }
}
