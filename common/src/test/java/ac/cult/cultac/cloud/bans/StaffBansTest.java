package ac.cult.cultac.cloud.bans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.Generation;
import ac.cult.cultac.platform.api.CommandOutcome;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
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
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
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
    private volatile String claimReply = "{\"leaseSeconds\":60}";
    private final Queue<Integer> resultStatuses = new ConcurrentLinkedQueue<>();
    private volatile Runnable beforePending = () -> {};

    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private final List<String> dispatched = Collections.synchronizedList(new ArrayList<>());
    private volatile RuntimeException dispatchFailure;
    private volatile CompletableFuture<CommandOutcome> outcome = CompletableFuture.completedFuture(CommandOutcome.RAN);
    private volatile boolean holdTasks;
    private volatile boolean schedulerClosed;
    private volatile Runnable beforeClaim = () -> {};
    private final List<Runnable> held = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong clock = new AtomicLong(NOW);
    private final Generation generation = new Generation();
    private final StaffBans.Handled handled = new StaffBans.Handled(StaffBans.MAX_HANDLED);

    private final StaffBans.Host host = new StaffBans.Host() {
        @Override
        public boolean online(UUID player) {
            return online.contains(player);
        }

        @Override
        public void schedule(Runnable task) {
            if (schedulerClosed) throw new RejectedExecutionException("scheduler shut down");
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
            beforeClaim.run();
            status = claimStatus;
            reply = claimReply;
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

    private long gets(String durations) {
        synchronized (seen) {
            return seen.stream()
                    .filter(s -> s.uri().startsWith("/api/v1/bans/pending?")
                            && s.uri().contains("&durations=" + durations + "&"))
                    .count();
        }
    }

    private static String query(String terms, String durations, String server, String vars, String cmds) {
        return "/api/v1/bans/pending?terms=" + terms + "&durations=" + durations + "&server=" + server + "&caps=terms"
                + "&vars=" + vars + "&cmds=" + cmds;
    }

    /** {@code ban} as a service that sends the term, the length token, the reason label and the silent flag. */
    private static JsonObject v2(JsonObject ban, String term, String duration, String reasonLabel, String silentFlag) {
        ban.addProperty("term", term);
        ban.addProperty("duration", duration);
        ban.addProperty("reasonLabel", reasonLabel);
        ban.addProperty("silentFlag", silentFlag);
        return ban;
    }

    private Seen resultFor(String id) {
        return results().stream()
                .filter(s -> s.uri().equals("/api/v1/bans/" + id + "/result"))
                .findFirst()
                .orElseThrow();
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
        assertEquals(query("permanent,timed,server", "all", "arena-1", "player,reason", "2009f92b3e562497"), get.uri());
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
    void aCommandThatFailsInsideThePlatformReportsWhatItThrew() throws Exception {
        outcome = CompletableFuture.failedFuture(new CompletionException(new RuntimeException(
                "Unable to invoke command ban Notch for console",
                new IllegalStateException("already banned: x"))));
        pending(ban(BAN, PLAYER, null, NOW - 60_000));

        bans().pollOnce().get(5, TimeUnit.SECONDS);

        assertResult(results().get(0), false, "already banned: x");
    }

    @Test
    void aWrappedThrowReportsItsCauseAndASilentOneSaysTheCommandFailed() throws Exception {
        dispatchFailure = new IllegalStateException(
                "Unhandled exception executing 'ban Notch'",
                new IllegalStateException("x".repeat(300), new NullPointerException()));
        pending(ban(BAN, PLAYER, null, NOW - 60_000));

        bans().pollOnce().get(5, TimeUnit.SECONDS);

        assertResult(results().get(0), false, "x".repeat(199) + "…");
        assertEquals(StaffBans.FAILED, StaffBans.why(new CompletionException(new NullPointerException()), "ban x"));
        IllegalStateException inner = new IllegalStateException("inner");
        RuntimeException outer = new RuntimeException("outer", inner);
        inner.initCause(outer);
        assertEquals("inner", StaffBans.why(outer, "ban x"));
    }

    @Test
    void aWrapperNamingTheCommandAroundASilentCauseNeverSendsTheCommand() throws Exception {
        outcome = CompletableFuture.failedFuture(new CompletionException(new RuntimeException(
                "Unable to invoke command ban Notch fly hacks for console", new NullPointerException())));
        pending(ban(BAN, PLAYER, null, NOW - 60_000));

        bans().pollOnce().get(5, TimeUnit.SECONDS);

        assertResult(results().get(0), false, StaffBans.FAILED);
        assertEquals(
                StaffBans.FAILED,
                StaffBans.why(
                        new IllegalStateException("could not run ban Notch fly hacks", new NullPointerException()),
                        "ban  Notch   fly hacks"));
    }

    @Test
    void aWrapperNamingTheCommandAroundAnInnerMessageSendsOnlyTheInnerMessage() throws Exception {
        dispatchFailure = new IllegalStateException(
                "Unhandled exception executing 'ban Notch fly hacks' in console",
                new IllegalStateException("already banned: x"));
        pending(ban(BAN, PLAYER, null, NOW - 60_000));

        bans().pollOnce().get(5, TimeUnit.SECONDS);

        assertResult(results().get(0), false, "already banned: x");
        assertFalse(results().get(0).body().contains("fly hacks"));
        assertEquals(
                "already banned: x",
                StaffBans.why(
                        new RuntimeException(
                                "failed: ban Notch fly hacks",
                                new IllegalStateException(
                                        "already banned: x",
                                        new IllegalStateException("ban Notch fly hacks was refused"))),
                        "ban Notch fly hacks"));
    }

    @Test
    void staffUuidAndEvidenceOfAnotherTypeThanStringAreAbsent() {
        List<JsonElement> others = List.of(
                new JsonPrimitive(true), new JsonPrimitive(123), new JsonObject(), new JsonArray());
        for (JsonElement other : others) {
            JsonObject json = ban(BAN, PLAYER, null, NOW - 60_000);
            json.add("staffUuid", other);
            json.add("evidence", other);

            StaffBans.Ban ban = StaffBans.ban(json);

            assertNull(ban.staffUuid(), other.toString());
            assertNull(ban.evidence(), other.toString());
            assertEquals("gban", BanCommand.fill("gban s=%staff_uuid% e=%evidence%", ban));
        }
    }

    @Test
    void staffUuidAndEvidenceReachTheCommandAndOlderServicesLeaveThemOut() throws Exception {
        JsonObject withBoth = ban(BAN, PLAYER, null, NOW - 60_000);
        withBoth.addProperty("staffUuid", "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
        withBoth.addProperty("evidence", "https://panel.example/replays/watch?window=w1");
        JsonObject older = ban(TIMED, OTHER, null, NOW - 60_000);
        JsonObject nulls = ban("2f0e0d0c-0b0a-4908-8706-050403020102", OTHER, null, NOW - 60_000);
        nulls.add("staffUuid", JsonNull.INSTANCE);
        nulls.add("evidence", JsonNull.INSTANCE);
        pending(withBoth, older, nulls);

        bans("arena-1", new StaffBans.Commands("gban staff=%staff_uuid% evidence=%evidence% %player%", ""))
                .pollOnce()
                .get(5, TimeUnit.SECONDS);

        assertEquals(
                List.of(
                        "gban Other",
                        "gban Other",
                        "gban staff=0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
                                + " evidence=https://panel.example/replays/watch?window=w1 Notch"),
                dispatched.stream().sorted().toList());
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
        bans().pollOnce().get(5, TimeUnit.SECONDS);
        waitFor(() -> results().size() == 3);
        bans().pollOnce().get(5, TimeUnit.SECONDS);

        assertEquals(List.of("ban Notch fly hacks"), dispatched);
        List<Seen> results = results();
        assertEquals(3, results.size());
        for (Seen result : results) assertResult(result, true, "dispatched");
        assertEquals(4, posts().size());
    }

    @Test
    void aResultRefusedWithAnyClientErrorIsNotRetried() throws Exception {
        String[] ids = {BAN, TIMED, "2f0e0d0c-0b0a-4908-8706-050403020109"};
        int[] statuses = {400, 401, 403};
        for (int i = 0; i < ids.length; i++) {
            resultStatuses.add(statuses[i]);
            pending(ban(ids[i], PLAYER, null, NOW - 60_000));
            bans().pollOnce().get(5, TimeUnit.SECONDS);
        }
        Thread.sleep(StaffBans.RESULT_RETRY_SECONDS * 10 + 200);

        assertEquals(3, results().size());
        assertEquals(3, dispatched.size());
    }

    @Test
    void aClaimAnsweredAfterTheSchedulerClosedIsLetGoUnreported() throws Exception {
        schedulerClosed = true;
        pending(ban(BAN, PLAYER, null, NOW - 60_000));

        bans().pollOnce().get(5, TimeUnit.SECONDS);

        assertTrue(dispatched.isEmpty());
        assertTrue(results().isEmpty());
        assertFalse(handled.contains(BAN));
    }

    @Test
    void aClaimAnsweredAfterAStopIsLetGoUnreported() throws Exception {
        pending(ban(BAN, PLAYER, null, NOW - 60_000));
        beforeClaim = generation::next;

        bans().pollOnce().get(5, TimeUnit.SECONDS);

        assertTrue(dispatched.isEmpty());
        assertEquals(1, posts().size());
        assertFalse(handled.contains(BAN));
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
    void thePendingQueryNamesTheTermsTheCommandsRunAndTheDurationsOlderServicesRead() throws Exception {
        bans("perm", new StaffBans.Commands("ban %player%", "")).pollOnce().get(5, TimeUnit.SECONDS);
        bans("temp", new StaffBans.Commands("", "tempban %player% %duration%"))
                .pollOnce()
                .get(5, TimeUnit.SECONDS);
        bans("both", new StaffBans.Commands("ban %player%", "tempban %player%"))
                .pollOnce()
                .get(5, TimeUnit.SECONDS);
        bans("one", new StaffBans.Commands("ban %silent% %player% %duration% %reason%", ""))
                .pollOnce()
                .get(5, TimeUnit.SECONDS);
        bans("lobby {1}/eu", new StaffBans.Commands("", "tempban %player% %seconds%"))
                .pollOnce()
                .get(5, TimeUnit.SECONDS);

        assertEquals(
                List.of(
                        query("permanent,server", "permanent", "perm", "player", "affd092b8fa901c6"),
                        query("permanent,timed", "timed", "temp", "duration,player", "882384187d9c1753"),
                        query("permanent,timed,server", "all", "both", "player", "8ce5c56ada419a79"),
                        query(
                                "permanent,timed,server",
                                "permanent",
                                "one",
                                "duration,player,reason,silent",
                                "a68e6f300adc315b"),
                        query("timed", "timed", "lobby+%7B1%7D%2Feu", "player,seconds", "d762761c7b23dbe4")),
                seen.stream().map(Seen::uri).toList());
    }

    @Test
    void thePendingQueryNamesThePlaceholdersEverySetCommandHolds() throws Exception {
        bans("lite", new StaffBans.Commands("litebans:ban %silent% %uuid% %duration% %reason% %foo%", ""))
                .pollOnce()
                .get(5, TimeUnit.SECONDS);
        bans("advanced", new StaffBans.Commands(
                        "advancedban:ban %silent% %player% %reason% (by %staff%)",
                        "advancedban:tempban %silent% %player% %duration% %reason%"))
                .pollOnce()
                .get(5, TimeUnit.SECONDS);

        assertEquals(
                List.of(
                        query(
                                "permanent,timed,server",
                                "permanent",
                                "lite",
                                "duration,reason,silent,uuid",
                                "f8c0ee28b54a45be"),
                        query("permanent,timed,server", "all", "advanced", "player,reason,silent", "32559674ea098b10")),
                seen.stream().map(Seen::uri).toList());
        assertEquals("", StaffBans.Commands.NONE.vars());
        assertEquals("player", new StaffBans.Commands("", "tempban %player% %%").vars());
        assertEquals(BanCommand.NAMES, BanCommand.values(StaffBans.ban(ban(BAN, PLAYER, null, NOW))).keySet());
    }

    @Test
    void theCommandsHashIsTheSha256OfBothStrippedCommandsJoinedByALineFeed() {
        assertEquals(
                "4e0a22648432a8b3",
                new StaffBans.Commands(
                                "ban %silent% %uuid% %duration% %reason%",
                                "tempban %silent% %uuid% %duration% %reason%")
                        .cmds());
        assertEquals(
                "4e0a22648432a8b3",
                new StaffBans.Commands(
                                "  ban %silent% %uuid% %duration% %reason%\n",
                                "\ttempban %silent% %uuid% %duration% %reason% ")
                        .cmds());
        assertEquals("6cb9884991b8f871", new StaffBans.Commands("ban %silent% %uuid% %duration% %reason%", null).cmds());
        assertEquals("01ba4719c80b6fe9", StaffBans.Commands.NONE.cmds());
        assertEquals("3b2f7fbae85d69d8", new StaffBans.Commands("ban é %player%", "tempban %player% %duration%").cmds());
    }

    @Test
    void eachTermRunsTheTemplateTheSelectionTableGives() {
        String plain = "ban %player% %reason%";
        String duration = "ban %player% %duration% %reason%";
        String seconds = "ban %player% seconds=%seconds% %reason%";
        String tempPlain = "tempban %player% %reason%";
        String tempDuration = "tempban %player% %duration% %reason%";
        String tempSeconds = "tempban %player% %seconds% %reason%";
        String all = "permanent,timed,server";
        // command, temp-command; the template a timed, a permanent and a server ban runs, "" when none; terms; durations
        String[][] rows = {
            {"", "", "", "", "", "", "timed"},
            {"", tempPlain, tempPlain, "", tempPlain, "timed,server", "timed"},
            {"", tempDuration, tempDuration, tempDuration, "", "permanent,timed", "timed"},
            {"", tempSeconds, tempSeconds, "", "", "timed", "timed"},
            {plain, "", "", plain, plain, "permanent,server", "permanent"},
            {plain, tempPlain, tempPlain, plain, plain, all, "all"},
            {plain, tempDuration, tempDuration, plain, plain, all, "all"},
            {plain, tempSeconds, tempSeconds, plain, plain, all, "all"},
            {duration, "", duration, duration, duration, all, "permanent"},
            {duration, tempPlain, tempPlain, duration, duration, all, "all"},
            {duration, tempDuration, tempDuration, duration, duration, all, "all"},
            {duration, tempSeconds, tempSeconds, duration, duration, all, "all"},
            {seconds, "", seconds, seconds, seconds, all, "permanent"},
            {seconds, tempPlain, tempPlain, seconds, seconds, all, "all"},
            {seconds, tempDuration, tempDuration, seconds, seconds, all, "all"},
            {seconds, tempSeconds, tempSeconds, seconds, seconds, all, "all"},
        };
        for (String[] row : rows) {
            StaffBans.Commands commands = new StaffBans.Commands(row[0], row[1]);
            String where = "command '" + row[0] + "', temp-command '" + row[1] + "'";
            assertEquals(row[2], commands.of(StaffBans.Term.TIMED), "timed, " + where);
            assertEquals(row[3], commands.of(StaffBans.Term.PERMANENT), "permanent, " + where);
            assertEquals(row[4], commands.of(StaffBans.Term.SERVER), "server, " + where);
            assertEquals(row[5], commands.terms(), "terms, " + where);
            assertEquals(row[6], commands.durations(), "durations, " + where);
        }
    }

    @Test
    void aTempCommandThatNeedsALengthNeverRunsABanTheServerLengthens() {
        StaffBans.Commands needs = new StaffBans.Commands("", "tempban %player% %duration% %reason%");
        assertEquals("", needs.of(StaffBans.Term.SERVER));
        assertEquals("permanent,timed", needs.terms());
        assertEquals(
                "no command here runs bans whose length the server decides: set command",
                StaffBans.Commands.unrunnable(StaffBans.Term.SERVER));
        StaffBans.Commands lengthless = new StaffBans.Commands("", "tempban %player% %reason%");
        assertEquals("tempban %player% %reason%", lengthless.of(StaffBans.Term.SERVER));
        assertEquals("timed,server", lengthless.terms());
    }
    @Test
    void oneCommandWithADurationRunsTimedPermanentAndServerBans() throws Exception {
        String third = "2f0e0d0c-0b0a-4908-8706-050403020102";
        pending(
                v2(ban(TIMED, OTHER, 2592000L, NOW - 60_000), "timed", "30d", "Cheating", "-s"),
                v2(ban(BAN, PLAYER, null, NOW - 60_000), "permanent", "", "Cheating", ""),
                v2(ban(third, PLAYER, null, NOW - 60_000), "server", "", "Cheating", "-s"));

        bans("arena-1", new StaffBans.Commands("litebans:ban %silent% %uuid% %duration% %reason%", ""))
                .pollOnce()
                .get(5, TimeUnit.SECONDS);

        assertEquals(
                List.of(
                        "litebans:ban -s " + PLAYER + " fly hacks",
                        "litebans:ban -s " + OTHER + " 30d fly hacks",
                        "litebans:ban " + PLAYER + " fly hacks"),
                dispatched.stream().sorted().toList());
        assertEquals(
                query(
                        "permanent,timed,server",
                        "permanent",
                        "arena-1",
                        "duration,reason,silent,uuid",
                        "bad382ba09940ec3"),
                seen.get(0).uri());
    }

    @Test
    void aBanWithAnUnknownTermOrATermThatDisagreesWithItsLengthIsNeverClaimed() throws Exception {
        String[][] cases = {{"forever", null}, {"timed", null}, {"permanent", "3600"}, {"server", "3600"}, {"TIMED", "3600"}};
        List<JsonObject> bans = new ArrayList<>();
        for (int i = 0; i < cases.length; i++) {
            Long seconds = cases[i][1] == null ? null : Long.parseLong(cases[i][1]);
            JsonObject ban = ban("2f0e0d0c-0b0a-4908-8706-05040302011" + i, PLAYER, seconds, NOW - 60_000);
            ban.addProperty("term", cases[i][0]);
            assertNull(StaffBans.ban(ban), String.join(" ", cases[i][0], String.valueOf(cases[i][1])));
            bans.add(ban);
        }
        pending(bans.toArray(JsonObject[]::new));

        bans().pollOnce().get(5, TimeUnit.SECONDS);

        assertTrue(posts().isEmpty());
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void theNewFieldsAreReadAndABanWithoutThemTakesItsTermFromItsLength() {
        StaffBans.Ban server =
                StaffBans.ban(v2(ban(BAN, PLAYER, null, NOW), "server", "", "Blacklisted modifications", "-s"));
        assertEquals(StaffBans.Term.SERVER, server.term());
        assertEquals("", server.duration());
        assertEquals("Blacklisted modifications", server.reasonLabel());
        assertEquals("-s", server.silentFlag());

        StaffBans.Ban permanent = StaffBans.ban(ban(BAN, PLAYER, null, NOW));
        assertEquals(StaffBans.Term.PERMANENT, permanent.term());
        assertNull(permanent.duration());
        assertNull(permanent.reasonLabel());
        assertEquals("", permanent.silentFlag());
        assertEquals(StaffBans.Term.TIMED, StaffBans.ban(ban(TIMED, PLAYER, 3600L, NOW)).term());

        JsonObject odd = ban(TIMED, PLAYER, 3600L, NOW);
        odd.add("term", JsonNull.INSTANCE);
        odd.addProperty("duration", 30);
        odd.add("reasonLabel", new JsonArray());
        odd.addProperty("silentFlag", true);
        StaffBans.Ban oddBan = StaffBans.ban(odd);
        assertEquals(StaffBans.Term.TIMED, oddBan.term());
        assertNull(oddBan.duration());
        assertNull(oddBan.reasonLabel());
        assertEquals("", oddBan.silentFlag());
    }

    @Test
    void aClaimAnsweredWithABanNoCommandHereRunsIsReportedNotRun() throws Exception {
        JsonObject answered = v2(ban(BAN, PLAYER, 3600L, NOW - 60_000), "timed", "1h", "Fly", "");
        JsonObject reply = new JsonObject();
        reply.addProperty("leaseSeconds", 60);
        reply.add("ban", answered);
        claimReply = reply.toString();
        pending(ban(BAN, PLAYER, null, NOW - 60_000));

        bans("arena-1", new StaffBans.Commands("ban %player% %reason%", ""))
                .pollOnce()
                .get(5, TimeUnit.SECONDS);

        assertTrue(dispatched.isEmpty());
        assertResult(
                results().get(0),
                false,
                "not run: no command here runs timed bans: set temp-command, or put %duration% in command");
    }

    @Test
    void aBanWhoseValueWouldAddAFlagIsReportedNotRun() throws Exception {
        JsonObject flagged = ban(BAN, PLAYER, null, NOW - 60_000);
        flagged.addProperty("reason", "fly hacks -s");
        JsonObject badSilent = v2(ban(TIMED, OTHER, 3600L, NOW - 60_000), "timed", "1h", "Fly", "s");
        pending(flagged, badSilent);

        bans("arena-1", new StaffBans.Commands(
                        "ban %silent% %player% %reason%", "tempban %silent% %player% %duration% %reason%"))
                .pollOnce()
                .get(5, TimeUnit.SECONDS);

        assertTrue(dispatched.isEmpty());
        assertResult(resultFor(BAN), false, "not run: %reason% has a flag word (- and a letter)");
        assertResult(resultFor(TIMED), false, "not run: %silent% is not one flag starting with -");
    }

    @Test
    void aBanFromAServiceWithoutTheNewFieldsRunsAsBefore() throws Exception {
        String staff = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
        String evidence = "https://panel.example/replays/watch?window=w1";
        JsonObject permanent = ban(BAN, PLAYER, null, NOW - 60_000);
        JsonObject timed = ban(TIMED, OTHER, 43200L, NOW - 60_000);
        for (JsonObject ban : List.of(permanent, timed)) {
            ban.addProperty("staffUuid", staff);
            ban.addProperty("evidence", evidence);
        }
        pending(permanent, timed);

        bans("arena-1", new StaffBans.Commands(
                        "ban %player% %reason% (%staff%) %link%",
                        "tempban %player% %duration% %reason% [%cheat%/%cheat_id%] %seconds%s by %staff_uuid%"
                                + " #%ban_id% %evidence% %uuid%"))
                .pollOnce()
                .get(5, TimeUnit.SECONDS);

        assertEquals(
                List.of(
                        "ban Notch fly hacks (Mod) https://panel.example/players/" + PLAYER,
                        "tempban Other 12h fly hacks [Fly/fly] 43200s by " + staff + " #" + TIMED + " " + evidence + " "
                                + OTHER),
                dispatched.stream().sorted().toList());
    }

    /** The {@code key=value} arguments before {@code reason=}, without empty ones, then the reason under its key. */
    private static Map<String, String> arguments(String command) {
        int reason = command.indexOf(" reason=");
        Map<String, String> arguments = new TreeMap<>();
        for (String word : command.substring(0, reason).split(" ")) {
            int equals = word.indexOf('=');
            if (equals > 0 && equals < word.length() - 1) {
                arguments.put(word.substring(0, equals), word.substring(equals + 1));
            }
        }
        arguments.put("reason", command.substring(reason + " reason=".length()));
        return arguments;
    }

    @Test
    void aKeyValueTemplatePairKeepsItsArgumentsForEveryTerm() throws Exception {
        String permanentTemplate = "gladiatorban target=%uuid% staff=%staff_uuid% seconds= ban=%ban_id%"
                + " evidence=%evidence% by=%staff% reason=%reason%";
        String timedTemplate = "gladiatorban target=%uuid% staff=%staff_uuid% seconds=%seconds% ban=%ban_id%"
                + " evidence=%evidence% by=%staff% reason=%reason%";
        String staff = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
        String evidence = "https://panel.example/replays/watch?window=w1";
        String olderPermanent = "2f0e0d0c-0b0a-4908-8706-050403020102";
        String olderTimed = "2f0e0d0c-0b0a-4908-8706-050403020103";
        JsonObject server = v2(ban(BAN, PLAYER, null, NOW - 60_000), "server", "", "Blacklisted modifications", "");
        server.addProperty("reason", "blacklisted_modifications");
        server.addProperty("staffUuid", staff);
        server.addProperty("evidence", evidence);
        JsonObject timed = v2(ban(TIMED, OTHER, 86400L, NOW - 60_000), "timed", "1d", "Griefing", "");
        JsonObject permanent = ban(olderPermanent, OTHER, null, NOW - 60_000);
        permanent.addProperty("reason", "exploiting");
        JsonObject timedWithStaff = ban(olderTimed, PLAYER, 604800L, NOW - 60_000);
        timedWithStaff.addProperty("staffUuid", staff);
        timedWithStaff.addProperty("evidence", evidence);
        pending(server, timed, permanent, timedWithStaff);

        bans("arena-1", new StaffBans.Commands(permanentTemplate, timedTemplate))
                .pollOnce()
                .get(5, TimeUnit.SECONDS);

        assertEquals(
                query(
                        "permanent,timed,server",
                        "all",
                        "arena-1",
                        "ban_id,evidence,reason,staff,staff_uuid,uuid",
                        "7631b1f461ccd59f"),
                seen.get(0).uri());
        String serverBan = "gladiatorban target=" + PLAYER + " staff=" + staff + " seconds= ban=" + BAN + " evidence="
                + evidence + " by=Mod reason=blacklisted_modifications";
        String timedBan = "gladiatorban target=" + OTHER + " seconds=86400 ban=" + TIMED + " by=Mod reason=fly hacks";
        String permanentBan = "gladiatorban target=" + OTHER + " seconds= ban=" + olderPermanent + " by=Mod reason=exploiting";
        String timedWithStaffBan = "gladiatorban target=" + PLAYER + " staff=" + staff + " seconds=604800 ban="
                + olderTimed + " evidence=" + evidence + " by=Mod reason=fly hacks";
        assertEquals(
                List.of(serverBan, timedBan, permanentBan, timedWithStaffBan).stream().sorted().toList(),
                dispatched.stream().sorted().toList());
        assertEquals(
                arguments("gladiatorban target=" + OTHER + " staff= seconds= ban=" + olderPermanent
                        + " evidence= by=Mod reason=exploiting"),
                arguments(permanentBan));
        assertEquals(
                arguments("gladiatorban target=" + OTHER + " staff= seconds=86400 ban=" + TIMED
                        + " evidence= by=Mod reason=fly hacks"),
                arguments(timedBan));
    }

    @Test
    void aGameplayBanRunsWithoutTheCheat() throws Exception {
        JsonObject gameplay = ban(BAN, PLAYER, null, NOW - 60_000);
        gameplay.add("cheat", JsonNull.INSTANCE);
        gameplay.add("cheatName", JsonNull.INSTANCE);
        pending(gameplay);

        bans("arena-1", new StaffBans.Commands("ban %player% [%cheat%|%cheat_id%] %reason%", ""))
                .pollOnce()
                .get(5, TimeUnit.SECONDS);

        assertEquals(List.of("ban Notch fly hacks"), dispatched);
        assertResult(results().get(0), true, "dispatched");
    }

    @Test
    void aServiceWithoutStaffBansIsAskedEveryFiveMinutes() throws Exception {
        pendingStatus = 404;

        assertEquals(StaffBans.ABSENT_POLL_SECONDS, bans().pollOnce().get(5, TimeUnit.SECONDS));
    }

    @Test
    void aReconfigureStopsTheOldChain() throws Exception {
        bans("arena-1", new StaffBans.Commands("ban %player%", "")).start();
        waitFor(() -> gets("permanent") >= 3);
        bans("arena-1", new StaffBans.Commands("", "tempban %player%")).start();
        waitFor(() -> gets("timed") >= 3);
        long old = gets("permanent");

        Thread.sleep(200);

        assertEquals(old, gets("permanent"));
        assertTrue(gets("timed") > 3);
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
