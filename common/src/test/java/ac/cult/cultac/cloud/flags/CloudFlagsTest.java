package ac.cult.cultac.cloud.flags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.cloud.CloudCombat;
import ac.cult.cultac.checks.impl.cloud.CloudExploitExperimental;
import ac.cult.cultac.checks.impl.cloud.CloudFlagCheck;
import ac.cult.cultac.checks.impl.cloud.CloudFlagCheckTest;
import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.local.LocalChannel;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CloudFlagsTest {
    private static final String KEY = "intake-secret-123";
    private static final UUID PLAYER = UUID.fromString("6a1f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6b");
    private static final UUID OTHER = UUID.fromString("7b2f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6c");
    private static final String FLAG = "0f0e0d0c-0b0a-4908-8706-050403020100";
    private static final String SECOND = "1f0e0d0c-0b0a-4908-8706-050403020101";

    private record Seen(String method, String uri, String authorization, String body) {}

    private record Raised(UUID player, Class<? extends CloudFlagCheck> type) {}

    private HttpServer server;
    private final ExecutorService calls = Executors.newSingleThreadExecutor(task -> new Thread(task, "flag-call"));
    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    private volatile JsonArray pending = new JsonArray();
    private volatile int pendingStatus = 200;
    private volatile int claimStatus = 200;
    private final Queue<Integer> resultStatuses = new ConcurrentLinkedQueue<>();

    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    /** Players who leave between their claim and their flag. */
    private final Set<UUID> leaving = ConcurrentHashMap.newKeySet();
    /** Players who leave once their flag is queued on their packet thread. */
    private final Set<UUID> departing = ConcurrentHashMap.newKeySet();
    /** Players whose check takes no violation. */
    private final Set<UUID> refusing = ConcurrentHashMap.newKeySet();

    private final List<Raised> raised = Collections.synchronizedList(new ArrayList<>());
    private final CloudFlags.Generation generation = new CloudFlags.Generation();
    private final CloudFlags.Handled handled = new CloudFlags.Handled(CloudFlags.MAX_HANDLED);

    private final CloudFlags.Host host = new CloudFlags.Host() {
        @Override
        public boolean online(UUID player) {
            return online.contains(player);
        }

        @Override
        public boolean schedule(UUID player, Class<? extends CloudFlagCheck> type, Consumer<CloudFlagCheck> flag) {
            if (leaving.contains(player)) return false;
            raised.add(new Raised(player, type));
            CloudFlagCheck check = mock(type);
            when(check.raise()).thenReturn(!refusing.contains(player));
            if (departing.contains(player)) online.remove(player);
            flag.accept(check);
            return true;
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
        CloudFlags.stop();
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
        if (uri.startsWith("/api/v1/cloud-flags/pending")) {
            status = pendingStatus;
            JsonObject answer = new JsonObject();
            answer.addProperty("pollSeconds", 1);
            answer.add("flags", pending);
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

    private CloudFlags flags(String serverId, CloudFlags.Host host) {
        return new CloudFlags(
                base(),
                KEY,
                serverId,
                HttpClient.newHttpClient(),
                calls,
                host,
                generation,
                handled,
                seconds -> CompletableFuture.delayedExecutor(seconds * 10, TimeUnit.MILLISECONDS));
    }

    private CloudFlags flags() {
        return flags("arena-1", host);
    }

    private static JsonObject flag(String id, UUID player, String flag) {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        object.addProperty("player", player.toString());
        object.addProperty("flag", flag);
        return object;
    }

    private void pending(JsonObject... flags) {
        JsonArray array = new JsonArray();
        for (JsonObject flag : flags) array.add(flag);
        pending = array;
    }

    private List<Seen> posts() {
        synchronized (seen) {
            return seen.stream().filter(s -> s.method().equals("POST")).toList();
        }
    }

    private List<Seen> results() {
        return posts().stream().filter(s -> s.uri().endsWith("/result")).toList();
    }

    private long gets(String serverId) {
        synchronized (seen) {
            return seen.stream()
                    .filter(s -> s.uri().equals("/api/v1/cloud-flags/pending?server=" + serverId))
                    .count();
        }
    }

    private static void assertResult(Seen result, String id, boolean ok, String message) {
        assertEquals("/api/v1/cloud-flags/" + id + "/result", result.uri());
        JsonObject body = new JsonObject();
        body.addProperty("server", "arena-1");
        body.addProperty("ok", ok);
        body.addProperty("message", message);
        assertEquals(body, JsonParser.parseString(result.body()));
    }

    @Test
    void anOnlinePlayersFlagIsClaimedRaisedAndReported() throws Exception {
        online.add(PLAYER);
        pending(flag(FLAG, PLAYER, "CloudCombat"));

        assertEquals(CloudFlags.MIN_POLL_SECONDS, flags().pollOnce().get(5, TimeUnit.SECONDS));

        assertEquals(List.of(new Raised(PLAYER, CloudCombat.class)), raised);
        Seen get = seen.get(0);
        assertEquals("GET", get.method());
        assertEquals("/api/v1/cloud-flags/pending?server=arena-1", get.uri());
        assertEquals("Bearer " + KEY, get.authorization());
        List<Seen> posts = posts();
        assertEquals(2, posts.size());
        assertEquals("/api/v1/cloud-flags/" + FLAG + "/claim", posts.get(0).uri());
        assertEquals("Bearer " + KEY, posts.get(0).authorization());
        assertEquals(
                JsonParser.parseString("{\"server\":\"arena-1\"}"),
                JsonParser.parseString(posts.get(0).body()));
        assertResult(posts.get(1), FLAG, true, "flagged");
    }

    @Test
    void onlyPlayersOnlineHereAreClaimed() throws Exception {
        online.add(PLAYER);
        pending(flag(FLAG, OTHER, "CloudCombat"), flag(SECOND, PLAYER, "CloudExploitExperimental"));

        flags().pollOnce().get(5, TimeUnit.SECONDS);

        assertEquals(List.of(new Raised(PLAYER, CloudExploitExperimental.class)), raised);
        assertTrue(posts().stream().noneMatch(post -> post.uri().contains(FLAG)));
        assertEquals("/api/v1/cloud-flags/" + SECOND + "/claim", posts().get(0).uri());
    }

    @Test
    void aLostClaimRaisesNothing() throws Exception {
        online.add(PLAYER);
        claimStatus = 409;
        pending(flag(FLAG, PLAYER, "CloudCombat"));

        flags().pollOnce().get(5, TimeUnit.SECONDS);

        assertEquals(1, posts().size());
        assertTrue(raised.isEmpty());
    }

    @Test
    void aFlagIsNeverRaisedTwiceAcrossRounds() throws Exception {
        online.add(PLAYER);
        pending(flag(FLAG, PLAYER, "CloudCombat"));
        CloudFlags flags = flags();

        flags.pollOnce().get(5, TimeUnit.SECONDS);
        flags.pollOnce().get(5, TimeUnit.SECONDS);

        assertEquals(1, raised.size());
        assertEquals(1, results().size());
    }

    @Test
    void aPlayerWhoLeftBeforeTheFlagGetsNoResult() throws Exception {
        online.add(PLAYER);
        leaving.add(PLAYER);
        pending(flag(FLAG, PLAYER, "CloudCombat"));

        flags().pollOnce().get(5, TimeUnit.SECONDS);

        assertEquals(1, posts().size());
        assertTrue(results().isEmpty());
        assertFalse(handled.contains(FLAG));
    }

    @Test
    void aPlayerWhoLeftOnceTheFlagWasQueuedGetsNoResult() throws Exception {
        online.add(PLAYER);
        departing.add(PLAYER);
        pending(flag(FLAG, PLAYER, "CloudCombat"));

        flags().pollOnce().get(5, TimeUnit.SECONDS);

        assertEquals(1, posts().size());
        assertTrue(results().isEmpty());
        assertFalse(handled.contains(FLAG));
    }

    @Test
    void aViolationTheCheckRefusesIsReportedAsNotFlagged() throws Exception {
        online.add(PLAYER);
        refusing.add(PLAYER);
        pending(flag(FLAG, PLAYER, "CloudCombat"));

        flags().pollOnce().get(5, TimeUnit.SECONDS);

        assertEquals(1, results().size());
        assertResult(results().get(0), FLAG, false, "the check took no violation");
    }

    @Test
    void anUnknownFlagIsReportedAndRaisesNothing() throws Exception {
        online.add(PLAYER);
        pending(flag(FLAG, PLAYER, "CloudSomethingNew"));

        flags().pollOnce().get(5, TimeUnit.SECONDS);

        assertTrue(raised.isEmpty());
        assertEquals(2, posts().size());
        assertResult(results().get(0), FLAG, false, "unknown flag");
    }

    @Test
    void aFailedResultIsRetriedUntilTaken() throws Exception {
        online.add(PLAYER);
        resultStatuses.add(503);
        pending(flag(FLAG, PLAYER, "CloudCombat"));

        flags().pollOnce().get(5, TimeUnit.SECONDS);
        waitFor(() -> results().size() >= 2);
        waitFor(() -> !handled.owes(FLAG));

        assertEquals(1, raised.size());
        assertResult(results().get(1), FLAG, true, "flagged");
    }

    @Test
    void aServiceWithoutCloudFlagsIsAskedEveryFiveMinutes() throws Exception {
        pendingStatus = 404;

        assertEquals(CloudFlags.ABSENT_POLL_SECONDS, flags().pollOnce().get(5, TimeUnit.SECONDS));
        assertEquals(300, CloudFlags.ABSENT_POLL_SECONDS);
    }

    @Test
    void aReconfigureStopsTheOldChain() throws Exception {
        flags("old", host).start();
        waitFor(() -> gets("old") >= 3);
        flags("new", host).start();
        waitFor(() -> gets("new") >= 3);
        long old = gets("old");

        Thread.sleep(200);

        assertEquals(old, gets("old"));
        assertTrue(gets("new") > 3);
    }

    @Test
    void anUploadThatIsOffSendsNothing() throws Exception {
        CloudFlags.configure(CloudSettings.OFF, "arena-1");
        CloudFlags.configure(new CloudSettings(base(), KEY, "{server}", "arena-1", List.of(5), false, false), null);
        Thread.sleep(200);

        assertTrue(seen.isEmpty());
    }

    @Test
    void theFlagRaisesOneViolationOfItsCheckOnThePacketThread() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        DefaultEventLoop loop = new DefaultEventLoop();
        LocalChannel channel = new LocalChannel();
        loop.register(channel).sync();
        CultPlayer player = new CultPlayer(TestUsers.create(new User.Profile(PLAYER, "Notch"), channel));
        try {
            AtomicReference<Thread> packetThread = new AtomicReference<>();
            loop.submit(() -> packetThread.set(Thread.currentThread())).sync();
            AtomicReference<Thread> flaggedOn = new AtomicReference<>();
            CloudFlags.Host packetHost = new CloudFlags.Host() {
                @Override
                public boolean online(UUID uuid) {
                    return uuid.equals(PLAYER);
                }

                @Override
                public boolean schedule(
                        UUID uuid, Class<? extends CloudFlagCheck> type, Consumer<CloudFlagCheck> flag) {
                    return CloudFlags.PlatformHost.schedule(player, type, check -> {
                        flaggedOn.set(Thread.currentThread());
                        flag.accept(check);
                    });
                }
            };
            pending(flag(FLAG, PLAYER, "CloudCombat"));

            flags("arena-1", packetHost).pollOnce().get(5, TimeUnit.SECONDS);

            assertSame(packetThread.get(), flaggedOn.get());
            for (String name : CloudFlagCheckTest.NAMES) {
                CloudFlagCheck check = player.checkManager.getCheck(CloudFlagCheck.of(name));
                assertEquals(name.equals("CloudCombat") ? 1 : 0, check.violations, 0, name);
            }
            assertFalse(player.getSetbackTeleportUtil().isPendingSetback());
            assertResult(results().get(0), FLAG, true, "flagged");
        } finally {
            loop.submit(player::onRemove).sync();
            channel.close().sync();
            loop.shutdownGracefully().sync();
        }
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out");
            Thread.sleep(10);
        }
    }
}
