package ac.cult.cultac.cloud.bans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.platform.api.CommandOutcome;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
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
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    private final Queue<Integer> resultStatuses = new ConcurrentLinkedQueue<>();
    private volatile Runnable beforePending = () -> {};

    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private final List<String> dispatched = Collections.synchronizedList(new ArrayList<>());
    private volatile RuntimeException dispatchFailure;
    private volatile CompletableFuture<CommandOutcome> outcome = CompletableFuture.completedFuture(CommandOutcome.RAN);
    private volatile boolean holdTasks;
    private final List<Runnable> held = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong clock = new AtomicLong(NOW);
    private final StaffBans.Generation generation = new StaffBans.Generation();
    private final StaffBans.Handled handled = new StaffBans.Handled(StaffBans.MAX_HANDLED);

    private final StaffBans.Host host = new StaffBans.Host() {
        @Override
        public boolean online(UUID player) {
            return online.contains(player);
        }

        @Override
        public void schedule(Runnable task) {
            if (holdTasks) {
                held.add(task);
            } else {
                task.run();
            }
        }

        @Override
        public CompletableFuture<CommandOutcome> dispatch(String command, UUID player) {
            if (dispatchFailure != null) throw dispatchFailure;
            dispatched.add(command);
            return outcome;
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
        generation.next();
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
            beforePending.run();
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
            Integer result = resultStatuses.poll();
            status = result == null ? 200 : result;
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
                generation,
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
                    .filter(s -> s.uri().startsWith("/api/v1/bans/pending?server=" + serverId + "&"))
                    .count();
        }
    }

    private List<Seen> results() {
        return posts().stream().filter(s -> s.uri().endsWith("/result")).toList();
    }

    private void assertResult(Seen result, boolean ok, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("server", "arena-1");
        body.addProperty("ok", ok);
        body.addProperty("message", message);
        assertEquals(body, JsonParser.parseString(result.body()));
    }

    @Test
    void aPendingBanIsClaimedDispatchedAndReported() throws Exception {
        pending(ban(BAN, PLAYER, null, NOW - 60_000));

        assertEquals(StaffBans.MIN_POLL_SECONDS, bans().pollOnce().get(5, TimeUnit.SECONDS));

        assertEquals(List.of("ban Notch fly hacks"), dispatched);
        Seen get = seen.get(0);
        assertEquals("GET", get.method());
        assertEquals("/api/v1/bans/pending?server=arena-1&durations=all", get.uri());
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
    void theResultSaysWhatThePlatformReported() throws Exception {
        List<CommandOutcome> outcomes =
                List.of(CommandOutcome.RAN, CommandOutcome.UNKNOWN, CommandOutcome.SENT_TO_BACKEND);
        for (int i = 0; i < outcomes.size(); i++) {
            outcome = CompletableFuture.completedFuture(outcomes.get(i));
            pending(ban("2f0e0d0c-0b0a-4908-8706-05040302010" + i, PLAYER, null, NOW - 60_000));

            bans().pollOnce().get(5, TimeUnit.SECONDS);

            assertResult(results().get(i), outcomes.get(i).ok(), outcomes.get(i).message());
        }
        assertResult(results().get(1), false, "unknown command");
        assertResult(results().get(2), true, "sent to backend");
    }

    @Test
    void aRunningDispatchIsAwaitedWithoutATimeout() throws Exception {
        CompletableFuture<CommandOutcome> later = new CompletableFuture<>();
        outcome = later;
        pending(ban(BAN, PLAYER, null, NOW - 60_000));

        CompletableFuture<Long> round = bans().pollOnce();
        waitFor(() -> dispatched.size() == 1);
        Thread.sleep(StaffBans.DISPATCH_TIMEOUT.toSeconds() * 10 + 200);

        assertTrue(results().isEmpty());
        later.complete(CommandOutcome.RAN);
        round.get(5, TimeUnit.SECONDS);
        assertResult(results().get(0), true, "dispatched");
    }

    @Test
    void aDispatchThatTimesOutNeverRunsLate() throws Exception {
        holdTasks = true;
        pending(ban(BAN, PLAYER, null, NOW - 60_000));

        bans().pollOnce().get(5, TimeUnit.SECONDS);
        assertResult(results().get(0), false, "not run: the server was busy");
        synchronized (held) {
            held.forEach(Runnable::run);
        }

        assertTrue(dispatched.isEmpty());
        assertEquals(2, posts().size());
    }

    @Test
    void aFailedResultIsRetriedUntilTakenAndNeverDispatchedAgain() throws Exception {
        resultStatuses.add(503);
        resultStatuses.add(503);
        pending(ban(BAN, PLAYER, null, NOW - 60_000));

        bans().pollOnce().get(5, TimeUnit.SECONDS);
        assertTrue(handled.owes(BAN));
        bans().pollOnce().get(5, TimeUnit.SECONDS);
        waitFor(() -> !handled.owes(BAN));
        bans().pollOnce().get(5, TimeUnit.SECONDS);

        assertEquals(List.of("ban Notch fly hacks"), dispatched);
        List<Seen> results = results();
        assertEquals(3, results.size());
        for (Seen result : results) assertResult(result, true, "dispatched");
        assertEquals(4, posts().size());
    }

    @Test
    void anOwedResultOutlivesTheHandledLimit() {
        StaffBans.Handled small = new StaffBans.Handled(1);
        assertTrue(small.add(BAN));
        small.owe(BAN);
        assertTrue(small.add(TIMED));

        assertFalse(small.add(BAN));
        small.paid(BAN);
        assertTrue(small.add(BAN));
    }

    @Test
    void aPollAnsweredAfterAReloadClaimsNothing() throws Exception {
        pending(ban(BAN, PLAYER, null, NOW - 60_000));
        StaffBans bans = bans();
        beforePending = generation::next;

        bans.pollOnce().get(5, TimeUnit.SECONDS);

        assertTrue(posts().isEmpty());
        assertFalse(handled.contains(BAN));
    }

    @Test
    void aClaimNotYetDispatchedAtAReloadIsLetGo() throws Exception {
        holdTasks = true;
        pending(ban(BAN, PLAYER, null, NOW - 60_000));

        CompletableFuture<Long> round = bans().pollOnce();
        waitFor(() -> held.size() == 1);
        generation.next();
        held.get(0).run();
        round.get(5, TimeUnit.SECONDS);

        assertTrue(dispatched.isEmpty());
        assertEquals(1, posts().size());
        assertFalse(handled.contains(BAN));
    }

    @Test
    void thePendingQueryAsksOnlyForBansACommandCovers() throws Exception {
        bans("perm", new StaffBans.Commands("ban %player%", "")).pollOnce().get(5, TimeUnit.SECONDS);
        bans("temp", new StaffBans.Commands("", "tempban %player%")).pollOnce().get(5, TimeUnit.SECONDS);
        bans("both", new StaffBans.Commands("ban %player%", "tempban %player%"))
                .pollOnce()
                .get(5, TimeUnit.SECONDS);

        assertEquals(
                List.of(
                        "/api/v1/bans/pending?server=perm&durations=permanent",
                        "/api/v1/bans/pending?server=temp&durations=timed",
                        "/api/v1/bans/pending?server=both&durations=all"),
                seen.stream().map(Seen::uri).toList());
    }

    @Test
    void aGameplayBanRunsWithTheCheatEmpty() throws Exception {
        JsonObject gameplay = ban(BAN, PLAYER, null, NOW - 60_000);
        gameplay.add("cheat", JsonNull.INSTANCE);
        gameplay.add("cheatName", JsonNull.INSTANCE);
        pending(gameplay);

        bans("arena-1", new StaffBans.Commands("ban %player% [%cheat%|%cheat_id%] %reason%", ""))
                .pollOnce()
                .get(5, TimeUnit.SECONDS);

        assertEquals(List.of("ban Notch [|] fly hacks"), dispatched);
        assertResult(results().get(0), true, "dispatched");
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
