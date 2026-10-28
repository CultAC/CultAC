package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.routing.MessageHandler.Origin;
import ac.cult.cultac.network.routing.NetworkPlatform.OnlinePlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import ac.cult.reload.api.HostContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NetworkServiceTest {
    private static final byte[] KEY = "a shared network secret of some length".getBytes(StandardCharsets.UTF_8);
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final NetworkSettings SETTINGS =
            new NetworkSettings("redis", "", "default", "", "", List.of(), "", "", "");

    private final AtomicLong clock = new AtomicLong(1_793_140_000_000L);
    private final FakeBroker broker = new FakeBroker();

    @BeforeAll
    static void logging() {
        OfflineCultTestBootstrap.installConfig();
    }

    /** The players a platform reports online. */
    private static final class Players implements NetworkPlatform {
        final Map<UUID, OnlinePlayer> online = new ConcurrentHashMap<>();

        Players with(UUID uuid, String name, String server) {
            online.put(uuid, new OnlinePlayer(uuid, name, server));
            return this;
        }

        @Override
        public Collection<OnlinePlayer> onlinePlayers() {
            return List.copyOf(online.values());
        }

        @Override
        public String address() {
            return "test:1";
        }
    }

    private NetworkService service(String instance, Players players, HostContext host, byte[] key, boolean transport) {
        return new NetworkService(
                SETTINGS,
                players,
                host,
                instance,
                key,
                transport ? broker.transport() : null,
                clock::get,
                null,
                null,
                new LogThrottle());
    }

    private NetworkService started(String instance, Players players) {
        NetworkService service = service(instance, players, null, KEY, true);
        service.start();
        return service;
    }

    private static HostContext host(Map<String, Object> shared, AtomicBoolean current) {
        return host(shared, current, UUID.randomUUID().toString());
    }

    @SuppressWarnings("unchecked")
    private static HostContext host(Map<String, Object> shared, AtomicBoolean current, String generation) {
        HostContext host = mock(HostContext.class);
        when(host.generationId()).thenReturn(generation);
        when(host.current()).thenAnswer(invocation -> current.get());
        when(host.shared(anyString(), any()))
                .thenAnswer(invocation -> shared.computeIfAbsent(
                        invocation.getArgument(0), key -> ((Supplier<Object>) invocation.getArgument(1)).get()));
        return host;
    }

    private List<JsonObject> rosterBodiesFrom(String instance) {
        List<JsonObject> bodies = new ArrayList<>();
        for (FakeBroker.Published message : broker.on("cultac.default.all")) {
            JsonObject envelope = JsonParser.parseString(
                            message.text().substring(message.text().indexOf('.') + 1))
                    .getAsJsonObject();
            if (envelope.get("from").getAsString().equals(instance)
                    && envelope.get("type").getAsString().equals("roster"))
                bodies.add(envelope.getAsJsonObject("body"));
        }
        return bodies;
    }

    @Test
    void twoInstancesSeeEachOthersPlayersAndForgetOneThatStops() {
        NetworkService a = started("p3", new Players().with(BOB, "Bob", null));
        NetworkService b = started("p7", new Players().with(ALICE, "Alice", "lobby"));
        a.tick();

        NetworkPlayer alice = a.locate("alice").orElseThrow();
        assertEquals(new NetworkPlayer(ALICE, "Alice", "p7", "lobby"), alice);
        assertEquals(new NetworkPlayer(BOB, "Bob", "p3", null), b.locate("Bob").orElseThrow());
        assertEquals(2, a.roster().size());

        b.stop();
        assertTrue(a.locate("Alice").isEmpty());
        assertEquals(1, a.roster().size());
        JsonObject goodbye = rosterBodiesFrom("p7").getLast();
        assertTrue(goodbye.get("full").getAsBoolean());
        assertTrue(goodbye.getAsJsonArray("players").isEmpty());
    }

    @Test
    void deltasFollowJoinsSwitchesAndQuits() {
        NetworkService a = started("p3", new Players());
        NetworkService b = started("p7", new Players());

        b.playerChanged(new OnlinePlayer(ALICE, "Alice", null));
        assertNull(a.locate("Alice").orElseThrow().server());
        b.playerChanged(new OnlinePlayer(ALICE, "Alice", "survival"));
        assertEquals("survival", a.locate("Alice").orElseThrow().server());
        assertEquals(1, a.roster().size());
        b.playerLeft(ALICE);
        assertTrue(a.locate("Alice").isEmpty());

        JsonObject quit = rosterBodiesFrom("p7").getLast();
        assertFalse(quit.get("full").getAsBoolean());
        assertEquals(ALICE.toString(), quit.getAsJsonArray("left").get(0).getAsString());
    }

    @Test
    void aSilentInstanceIsForgottenFortyFiveSecondsAfterItsLastMessage() {
        NetworkService a = started("p3", new Players());
        a.on("probe", (envelope, origin) -> {});
        NetworkService b = started("p7", new Players().with(ALICE, "Alice", "lobby"));
        broker.published.clear();

        clock.addAndGet(40_000);
        assertTrue(b.send("p3", "probe", new JsonObject()));
        clock.addAndGet(45_000);
        assertTrue(a.locate("Alice").isPresent());
        clock.addAndGet(1);
        assertTrue(a.locate("Alice").isEmpty());
        assertTrue(a.roster().isEmpty());
        a.tick();
        assertTrue(a.peerFlags().isEmpty());
    }

    @Test
    void localPlayersWinOverAStaleRemoteEntry() {
        Players here = new Players();
        NetworkService a = started("p3", here);
        started("p7", new Players().with(ALICE, "Alice", "lobby"));
        here.with(ALICE, "Alice", null);

        assertEquals("p3", a.locate("ALICE").orElseThrow().instance());
        assertEquals(List.of(new NetworkPlayer(ALICE, "Alice", "p3", null)), List.copyOf(a.roster()));
    }

    @Test
    void locateMatchesAUuidOrANameInAnyCase() {
        NetworkService a = started("p3", new Players().with(BOB, "Bob", null));
        started("p7", new Players().with(ALICE, "Alice", "lobby"));

        assertEquals("p7", a.locate(ALICE.toString()).orElseThrow().instance());
        assertEquals("p7", a.locate("aLiCe").orElseThrow().instance());
        assertEquals("p3", a.locate(BOB.toString()).orElseThrow().instance());
        assertTrue(a.locate("Carol").isEmpty());
        assertTrue(a.locate(UUID.randomUUID().toString()).isEmpty());
    }

    @Test
    void onlyTheCurrentGenerationPublishesTheRosterAndADrainingOneStillListens() {
        AtomicBoolean current = new AtomicBoolean(false);
        NetworkService draining =
                service("p3", new Players().with(BOB, "Bob", null), host(new HashMap<>(), current), KEY, true);
        draining.start();
        draining.tick();
        draining.playerChanged(new OnlinePlayer(BOB, "Bob", "lobby"));
        draining.playerLeft(BOB);
        assertTrue(rosterBodiesFrom("p3").isEmpty());

        started("p7", new Players().with(ALICE, "Alice", "lobby"));
        assertEquals("p7", draining.locate("Alice").orElseThrow().instance());

        draining.stop();
        assertTrue(rosterBodiesFrom("p3").isEmpty());

        current.set(true);
        NetworkService promoted =
                service("p3", new Players().with(BOB, "Bob", null), host(new HashMap<>(), current), KEY, true);
        promoted.start();
        assertEquals(1, rosterBodiesFrom("p3").size());
        promoted.tick();
        assertEquals(2, rosterBodiesFrom("p3").size());
        promoted.stop();
        assertTrue(rosterBodiesFrom("p3").getLast().getAsJsonArray("players").isEmpty());
    }

    @Test
    void atShutdownTheGenerationThatPublishedLastSaysGoodbyeAndASupersededOneDoesNot() {
        Map<String, Object> shared = new HashMap<>();
        AtomicBoolean olderCurrent = new AtomicBoolean(true);
        AtomicBoolean newerCurrent = new AtomicBoolean(false);
        Players here = new Players().with(BOB, "Bob", "lobby");
        NetworkService older = service("p3", here, host(shared, olderCurrent, "g1"), KEY, true);
        older.start();
        NetworkService peer = started("p7", new Players());

        olderCurrent.set(false);
        newerCurrent.set(true);
        NetworkService newer = service("p3", here, host(shared, newerCurrent, "g2"), KEY, true);
        newer.start();
        assertEquals("p3", peer.locate("Bob").orElseThrow().instance());

        newerCurrent.set(false);
        int published = rosterBodiesFrom("p3").size();
        older.stop();
        assertEquals(published, rosterBodiesFrom("p3").size());
        assertTrue(peer.locate("Bob").isPresent());

        newer.stop();
        JsonObject goodbye = rosterBodiesFrom("p3").getLast();
        assertEquals(published + 1, rosterBodiesFrom("p3").size());
        assertTrue(goodbye.get("full").getAsBoolean());
        assertTrue(goodbye.getAsJsonArray("players").isEmpty());
        assertTrue(peer.locate("Bob").isEmpty());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out");
            Thread.sleep(20);
        }
    }

    /** One receiving thread that counts the messages handed to it. */
    private static final class Receiver extends ThreadPoolExecutor {
        final AtomicInteger queued = new AtomicInteger();

        Receiver() {
            super(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        public void execute(Runnable task) {
            queued.incrementAndGet();
            super.execute(task);
        }
    }

    private NetworkService withReceiver(String instance, Receiver receiver, LogThrottle drops) {
        return new NetworkService(
                SETTINGS, new Players(), null, instance, KEY, broker.transport(), clock::get, null, receiver, drops);
    }

    @Test
    void aMessageOverTheSizeCapIsDroppedBeforeItIsQueued() {
        Receiver receiver = new Receiver();
        try {
            NetworkService a = withReceiver("p3", receiver, new LogThrottle());
            a.start();
            int queued = receiver.queued.get();
            broker.transport().publish("cultac.default.all", "x".repeat(EnvelopeCodec.MAX_BYTES - 1) + "\u00e9");
            broker.transport().publish("cultac.default.p3", "y".repeat(EnvelopeCodec.MAX_BYTES + 1));
            assertEquals(queued, receiver.queued.get());
            broker.transport().publish("cultac.default.p3", "z".repeat(EnvelopeCodec.MAX_BYTES));
            assertEquals(queued + 1, receiver.queued.get());
            a.stop();
        } finally {
            receiver.shutdownNow();
        }
    }

    @Test
    void aFullReceiveQueueDropsWarnsOnceAWindowAndTheRosterKeepsExpiring() throws Exception {
        Receiver receiver = new Receiver();
        AtomicLong nanos = new AtomicLong();
        CountDownLatch release = new CountDownLatch(1);
        try (CapturedLog log = new CapturedLog(Level.WARNING, "Dropped a received network message")) {
            NetworkService a = withReceiver("p3", receiver, new LogThrottle(TimeUnit.MINUTES.toNanos(1), nanos::get));
            a.start();
            NetworkService b = started("p7", new Players().with(ALICE, "Alice", "lobby"));
            await(() -> a.locate("Alice").isPresent());
            a.on("probe", (envelope, origin) -> {
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });

            for (int i = 0; i < NetworkService.MAX_QUEUED_MESSAGES + 5; i++) b.send("p3", "probe", new JsonObject());
            assertEquals(1, log.messages().size());
            nanos.addAndGet(TimeUnit.MINUTES.toNanos(1));
            b.send("p3", "probe", new JsonObject());
            assertEquals(2, log.messages().size());
            assertTrue(
                    log.messages().get(1).endsWith("(4 more since the last report)"),
                    log.messages().get(1));

            clock.addAndGet(RosterCache.EXPIRY_MILLIS + 1);
            broker.published.clear();
            a.tick();
            assertTrue(a.locate("Alice").isEmpty());
            assertEquals(1, broker.on("cultac.default.all").size());
        } finally {
            release.countDown();
            receiver.shutdownNow();
        }
    }

    @Test
    void theFirstBadSignatureWarnsAndTheNextAreThrottled() {
        try (CapturedLog log = new CapturedLog(Level.WARNING, "Dropped a network message with a bad signature")) {
            started("p3", new Players());
            NetworkService stranger = service(
                    "p9",
                    new Players().with(ALICE, "Alice", "lobby"),
                    null,
                    "a different secret".getBytes(StandardCharsets.UTF_8),
                    true);
            stranger.start();
            assertEquals(1, log.messages().size());
            stranger.tick();
            stranger.tick();
            assertEquals(1, log.messages().size());
        }
    }

    @Test
    void theBusReachesEveryGenerationHereWithoutATransport() {
        Map<String, Object> shared = new HashMap<>();
        AtomicBoolean current = new AtomicBoolean(true);
        NetworkService older = service("p3", new Players(), host(shared, current), null, false);
        NetworkService newer = service("p3", new Players(), host(shared, current), null, false);
        older.start();
        newer.start();
        List<String> heard = new ArrayList<>();
        older.on(
                "probe",
                (envelope, origin) ->
                        heard.add("older " + origin + " " + envelope.body().get("n")));
        newer.on(
                "probe",
                (envelope, origin) ->
                        heard.add("newer " + origin + " " + envelope.body().get("n")));

        JsonObject body = new JsonObject();
        body.addProperty("n", 1);
        assertTrue(newer.sendLocal("probe", body));
        assertEquals(List.of("older LOCAL 1", "newer LOCAL 1"), heard);

        heard.clear();
        older.stop();
        body.addProperty("n", 2);
        assertTrue(newer.send("p3", "probe", body));
        assertEquals(List.of("newer LOCAL 2"), heard);
        assertFalse(newer.broadcast("probe", body));
        assertFalse(newer.enabled());
        assertTrue(broker.published.isEmpty());
    }

    @Test
    void withoutAHostTheBusReachesOnlyThisGeneration() {
        NetworkService alone = service("p3", new Players(), null, null, false);
        alone.start();
        List<Origin> heard = new ArrayList<>();
        alone.on("probe", (envelope, origin) -> heard.add(origin));
        assertTrue(alone.sendLocal("probe", new JsonObject()));
        assertEquals(List.of(Origin.LOCAL), heard);
        alone.stop();
        assertFalse(alone.sendLocal("probe", new JsonObject()));
    }

    @Test
    void messagesForThisInstanceNeverReachTheBroker() {
        NetworkService a = started("p3", new Players());
        List<Origin> heard = new ArrayList<>();
        a.on("probe", (envelope, origin) -> heard.add(origin));
        broker.published.clear();

        assertTrue(a.send("p3", "probe", new JsonObject()));
        assertEquals(List.of(Origin.LOCAL), heard);
        assertTrue(broker.published.isEmpty());
    }

    @Test
    void handlersSeeHowAMessageArrived() {
        NetworkService a = started("p3", new Players());
        NetworkService b = started("p7", new Players());
        List<String> heard = new ArrayList<>();
        a.on("probe", (envelope, origin) -> heard.add(origin + " from " + envelope.from()));
        b.on("probe", (envelope, origin) -> heard.add("b heard its own " + origin));

        assertTrue(b.send("p3", "probe", new JsonObject()));
        assertTrue(b.broadcast("probe", new JsonObject()));
        assertEquals(List.of("DIRECT from p7", "BROADCAST from p7"), heard);
    }

    @Test
    void aTypeWithoutAHandlerIsDroppedAndARemovedHandlerHearsNothing() {
        NetworkService a = started("p3", new Players());
        NetworkService b = started("p7", new Players());
        List<String> heard = new ArrayList<>();
        NetworkService.Registration registration = a.on("probe", (envelope, origin) -> heard.add("probe"));
        b.send("p3", "other", new JsonObject());
        registration.close();
        b.send("p3", "probe", new JsonObject());
        assertTrue(heard.isEmpty());
    }

    @Test
    void anInstanceWithAnotherSecretIsIgnored() {
        NetworkService a = started("p3", new Players());
        NetworkService stranger = service(
                "p9",
                new Players().with(ALICE, "Alice", "lobby"),
                null,
                "a different secret".getBytes(StandardCharsets.UTF_8),
                true);
        stranger.start();
        assertTrue(a.locate("Alice").isEmpty());
        assertTrue(a.peerFlags().isEmpty());
    }

    @Test
    void rosterFlagsReachThePeers() {
        NetworkService a = started("p3", new Players());
        NetworkService b = started("p7", new Players());
        b.flag("global-verbose", new JsonPrimitive(true));
        b.tick();
        assertTrue(a.peerFlags().get("p7").get("global-verbose").getAsBoolean());
        b.flag("global-verbose", null);
        b.tick();
        assertFalse(a.peerFlags().get("p7").has("global-verbose"));
    }

    @Test
    void runRunsALocalPlayersCommandHereAndCannotYetForwardOne() {
        NetworkService a = started("p3", new Players().with(BOB, "Bob", null));
        started("p7", new Players().with(ALICE, "Alice", "lobby"));
        Sender sender = mock(Sender.class);

        assertTrue(
                a.run(sender, "cult profile Bob", a.locate("Bob").orElseThrow()).join());
        verify(sender).performCommand("cult profile Bob");

        assertFalse(a.run(sender, "cult profile Alice", a.locate("Alice").orElseThrow())
                .join());
        verify(sender, never()).performCommand("cult profile Alice");
        verify(sender).sendMessage(any(Component.class));
    }

    @Test
    void enabledOnlyWhileATransportRuns() {
        NetworkService withTransport = service("p3", new Players(), null, KEY, true);
        assertFalse(withTransport.enabled());
        withTransport.start();
        assertTrue(withTransport.enabled());
        assertEquals("Fake", withTransport.transportName());
        withTransport.stop();
        assertFalse(withTransport.enabled());

        NetworkService without = service("p3", new Players(), null, KEY, false);
        without.start();
        assertFalse(without.enabled());
        assertEquals("none", without.transportName());
    }
}
