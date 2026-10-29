package ac.cult.cultac.network.routing;

import static ac.cult.cultac.network.routing.TestNetworks.KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.routing.NetworkPlatform.OnlinePlayer;
import ac.cult.cultac.network.routing.TestNetworks.Players;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import ac.cult.reload.api.HostContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NetworkServiceTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final AtomicLong clock = new AtomicLong(1_793_140_000_000L);
    private final FakeBroker broker = new FakeBroker();
    private final TestNetworks networks = new TestNetworks(broker, clock);

    @BeforeAll
    static void logging() {
        OfflineCultTestBootstrap.installConfig();
    }

    private NetworkService service(String instance, Players players, HostContext host, byte[] key, boolean transport) {
        return builder(instance, players, host, transport ? broker.transport() : null)
                .key(key)
                .build();
    }

    private NetworkService service(String instance, Players players, HostContext host, MessageTransport transport) {
        return builder(instance, players, host, transport).build();
    }

    private TestNetworks.Builder builder(
            String instance, Players players, @Nullable HostContext host, @Nullable MessageTransport transport) {
        return networks.service(instance).platform(players).host(host).transport(transport);
    }

    private NetworkService started(String instance, Players players) {
        return networks.service(instance).platform(players).start();
    }

    private static HostContext host(Map<String, Object> shared, AtomicBoolean current) {
        return host(shared, current, UUID.randomUUID().toString());
    }

    private static HostContext host(Map<String, Object> shared, AtomicBoolean current, String generation) {
        return TestNetworks.host(shared, current::get, generation);
    }

    private List<JsonObject> rosterBodiesFrom(String instance) {
        List<JsonObject> bodies = new ArrayList<>();
        for (FakeBroker.Published message : broker.on(TestNetworks.channel(NetworkSettings.ALL))) {
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
        assertTrue(goodbye.getAsJsonObject("servers").isEmpty());
    }

    @Test
    void deltasFollowJoinsSwitchesAndQuits() {
        NetworkService a = started("p3", new Players());
        NetworkService b = started("p7", new Players());

        b.playerChanged(new OnlinePlayer(ALICE, "Alice", null));
        assertTrue(a.locate("Alice").isEmpty());
        b.sendDelta();
        assertNull(a.locate("Alice").orElseThrow().server());
        b.playerChanged(new OnlinePlayer(ALICE, "Alice", "survival"));
        b.sendDelta();
        assertEquals("survival", a.locate("Alice").orElseThrow().server());
        assertEquals(1, a.roster().size());
        b.playerLeft(ALICE);
        b.sendDelta();
        assertTrue(a.locate("Alice").isEmpty());

        JsonObject quit = rosterBodiesFrom("p7").getLast();
        assertFalse(quit.get("full").getAsBoolean());
        assertEquals(ALICE.toString(), quit.getAsJsonArray("left").get(0).getAsString());
    }

    @Test
    void aBrandArrivingAfterJoinRidesTheNextDelta() {
        Players here = new Players().with(ALICE, "Alice", "lobby");
        NetworkService a = started("p3", new Players());
        NetworkService b = started("p7", here);
        assertNull(a.locate("Alice").orElseThrow().brand());

        b.brandChanged(ALICE, "§bLunar\u0000Client ");
        b.brandChanged(BOB, "fabric");
        b.sendDelta();
        JsonObject delta = rosterBodiesFrom("p7").getLast();
        assertFalse(delta.get("full").getAsBoolean());
        assertEquals(
                ALICE.toString(),
                delta.getAsJsonObject("brands")
                        .getAsJsonArray("LunarClient")
                        .get(0)
                        .getAsString());
        assertEquals(
                new NetworkPlayer(ALICE, "Alice", "p7", "lobby", "LunarClient"),
                a.locate("Alice").orElseThrow());
        assertTrue(a.locate("Bob").isEmpty());

        here.with(ALICE, "Alice", "lobby", "LunarClient");
        assertEquals("LunarClient", b.locate("Alice").orElseThrow().brand());
        b.tick();
        assertEquals("LunarClient", a.locate("Alice").orElseThrow().brand());
    }

    @Test
    void theReportsOfOneWindowGoOutAsOneDelta() {
        NetworkService a = started("p3", new Players());
        NetworkService b = started("p7", new Players());
        int published = rosterBodiesFrom("p7").size();

        b.playerChanged(new OnlinePlayer(ALICE, "Alice", null));
        b.playerChanged(new OnlinePlayer(BOB, "Bob", "lobby"));
        b.playerChanged(new OnlinePlayer(ALICE, "Alice", "survival"));
        assertEquals(published, rosterBodiesFrom("p7").size());
        b.sendDelta();
        b.sendDelta();

        assertEquals(published + 1, rosterBodiesFrom("p7").size());
        JsonObject delta = rosterBodiesFrom("p7").getLast();
        assertFalse(delta.get("full").getAsBoolean());
        assertEquals("survival", a.locate("Alice").orElseThrow().server());
        assertEquals("lobby", a.locate("Bob").orElseThrow().server());
    }

    @Test
    void aJoinThenAQuitSendsTheQuitOnlyAndAQuitThenAJoinListsThePlayer() {
        NetworkService a = started("p3", new Players());
        NetworkService b = started("p7", new Players());

        b.playerChanged(new OnlinePlayer(ALICE, "Alice", "lobby"));
        b.playerLeft(ALICE);
        b.sendDelta();
        JsonObject quit = rosterBodiesFrom("p7").getLast();
        assertFalse(quit.get("full").getAsBoolean());
        assertTrue(quit.getAsJsonObject("servers").isEmpty());
        assertEquals(1, quit.getAsJsonArray("left").size());
        assertEquals(ALICE.toString(), quit.getAsJsonArray("left").get(0).getAsString());
        assertTrue(a.locate("Alice").isEmpty());

        b.playerLeft(ALICE);
        b.playerChanged(new OnlinePlayer(ALICE, "Alice", "survival"));
        b.sendDelta();
        JsonObject join = rosterBodiesFrom("p7").getLast();
        assertFalse(join.has("left") && !join.getAsJsonArray("left").isEmpty());
        assertEquals("survival", a.locate("Alice").orElseThrow().server());
    }

    @Test
    void aFullRosterTakesWhatIsStillToSend() {
        Players here = new Players();
        NetworkService a = started("p3", new Players());
        NetworkService b = started("p7", here);
        here.with(ALICE, "Alice", "lobby");
        b.playerChanged(new OnlinePlayer(ALICE, "Alice", "lobby"));
        b.verbose(() -> true);
        b.verboseChanged();

        b.tick();
        int published = rosterBodiesFrom("p7").size();
        assertTrue(rosterBodiesFrom("p7").getLast().get("full").getAsBoolean());
        assertEquals("lobby", a.locate("Alice").orElseThrow().server());
        assertTrue(a.peersWantVerbose());
        b.sendDelta();
        assertEquals(published, rosterBodiesFrom("p7").size());
    }

    @Test
    void aSilentInstanceIsForgottenOnceTheExpiryPassesAfterItsLastMessage() {
        NetworkService a = started("p3", new Players());
        a.on("probe", envelope -> {});
        NetworkService b = started("p7", new Players().with(ALICE, "Alice", "lobby"));
        broker.published.clear();

        clock.addAndGet(RosterCache.EXPIRY_MILLIS - 5_000);
        assertTrue(b.send("p3", "probe", new JsonObject()));
        clock.addAndGet(RosterCache.EXPIRY_MILLIS);
        assertTrue(a.locate("Alice").isPresent());
        clock.addAndGet(1);
        assertTrue(a.locate("Alice").isEmpty());
        assertTrue(a.roster().isEmpty());
        a.tick();
        assertFalse(a.peersWantVerbose());
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
        draining.verbose(() -> true);
        draining.verboseChanged();
        draining.sendDelta();
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
        assertTrue(rosterBodiesFrom("p3").getLast().getAsJsonObject("servers").isEmpty());
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
        assertTrue(goodbye.getAsJsonObject("servers").isEmpty());
        assertTrue(peer.locate("Bob").isEmpty());
    }

    @Test
    void aGenerationPausedAfterItsCurrentCheckCannotTakeTheGoodbyeFromTheOneThatReplacedIt() throws Exception {
        Map<String, Object> shared = new ConcurrentHashMap<>();
        AtomicBoolean olderCurrent = new AtomicBoolean(true);
        AtomicBoolean newerCurrent = new AtomicBoolean(false);
        AtomicBoolean pauseOlder = new AtomicBoolean();
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        HostContext olderHost = TestNetworks.host(
                shared,
                () -> {
                    boolean current = olderCurrent.get();
                    if (pauseOlder.compareAndSet(true, false)) {
                        paused.countDown();
                        resume.await();
                    }
                    return current;
                },
                "g1");
        Players here = new Players().with(BOB, "Bob", "lobby");
        NetworkService older = service("p3", here, olderHost, KEY, true);
        older.start();
        NetworkService peer = started("p7", new Players());
        NetworkService newer = service("p3", here, host(shared, newerCurrent, "g2"), KEY, true);

        pauseOlder.set(true);
        Thread tick = new Thread(older::tick);
        tick.start();
        assertTrue(paused.await(10, TimeUnit.SECONDS));
        olderCurrent.set(false);
        newerCurrent.set(true);
        Thread promotion = new Thread(newer::start);
        promotion.start();
        await(() -> promotion.getState() == Thread.State.TERMINATED || promotion.getState() == Thread.State.BLOCKED);
        resume.countDown();
        tick.join(10_000);
        promotion.join(10_000);
        assertFalse(tick.isAlive());
        assertFalse(promotion.isAlive());

        newerCurrent.set(false);
        older.stop();
        assertTrue(peer.locate("Bob").isPresent());
        newer.stop();
        assertTrue(peer.locate("Bob").isEmpty());
        assertTrue(rosterBodiesFrom("p3").getLast().getAsJsonObject("servers").isEmpty());
    }

    @Test
    void anOlderGenerationsGoodbyeDeliveredAfterTheNewerSnapshotLeavesThePlayersListed() {
        Map<String, Object> shared = new ConcurrentHashMap<>();
        AtomicBoolean olderCurrent = new AtomicBoolean(true);
        AtomicBoolean newerCurrent = new AtomicBoolean(false);
        Players here = new Players().with(BOB, "Bob", "lobby");
        FakeBroker.FakeTransport olderTransport = broker.transport();
        NetworkService older = service("p3", here, host(shared, olderCurrent, "g1"), olderTransport);
        older.start();
        NetworkService peer = started("p7", new Players());
        older.tick();
        assertEquals("p3", peer.locate("Bob").orElseThrow().instance());

        olderTransport.hold();
        olderCurrent.set(false);
        older.stop();
        newerCurrent.set(true);
        NetworkService newer = service("p3", here, host(shared, newerCurrent, "g2"), broker.transport());
        newer.start();
        olderTransport.release();

        assertTrue(rosterBodiesFrom("p3").getLast().getAsJsonObject("servers").isEmpty());
        assertEquals("p3", peer.locate("Bob").orElseThrow().instance());
        newer.tick();
        assertEquals("p3", peer.locate("Bob").orElseThrow().instance());
    }

    private static Map<String, Object> rosterOrder(long next) {
        Map<String, Object> shared = new ConcurrentHashMap<>();
        shared.put(NetworkService.ROSTER_ORDER_KEY, new AtomicLong(next));
        return shared;
    }

    @Test
    void aRestartedInstanceIsHeardARosterNumberedLowerIsDroppedAndAClockRegressionHealsAfterExpiry() {
        AtomicBoolean current = new AtomicBoolean(true);
        NetworkService peer = started("p7", new Players());
        NetworkService crashed = service(
                "p3", new Players().with(BOB, "Bob", "lobby"), host(rosterOrder(5_000), current), broker.transport());
        crashed.start();
        crashed.tick();
        assertEquals("p3", peer.locate("Bob").orElseThrow().instance());

        NetworkService restarted = service(
                "p3",
                new Players().with(ALICE, "Alice", "lobby"),
                host(rosterOrder(6_000), current),
                broker.transport());
        restarted.start();
        assertTrue(peer.locate("Bob").isEmpty());
        assertEquals("p3", peer.locate("Alice").orElseThrow().instance());

        NetworkService wentBack = service(
                "p3", new Players().with(BOB, "Bob", "lobby"), host(rosterOrder(4_000), current), broker.transport());
        wentBack.start();
        wentBack.tick();
        assertTrue(peer.locate("Bob").isEmpty());
        assertEquals("p3", peer.locate("Alice").orElseThrow().instance());

        clock.addAndGet(RosterCache.EXPIRY_MILLIS + 1);
        wentBack.tick();
        assertEquals("p3", peer.locate("Bob").orElseThrow().instance());
        assertTrue(peer.locate("Alice").isEmpty());
    }

    @Test
    void aServiceStartedAgainWithoutAHostIsHeardAfterItsGoodbye() {
        NetworkService peer = started("p7", new Players());
        NetworkService first = started("p3", new Players().with(BOB, "Bob", "lobby"));
        first.tick();
        first.stop();
        assertTrue(peer.locate("Bob").isEmpty());

        started("p3", new Players().with(ALICE, "Alice", "lobby"));
        assertEquals("p3", peer.locate("Alice").orElseThrow().instance());
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

    /** One scheduler thread; the tasks scheduled on it at a fixed rate run each time {@link #fire} asks, and only then. */
    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        private final List<Runnable> periodic = new CopyOnWriteArrayList<>();

        ManualScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            periodic.add(command);
            return schedule(() -> {}, 1, TimeUnit.DAYS);
        }

        /** Runs the fixed-rate tasks on the scheduler thread, in the order they were scheduled, and waits for them. */
        void fire() throws Exception {
            submit(() -> periodic.forEach(Runnable::run)).get(10, TimeUnit.SECONDS);
        }
    }

    private NetworkService withReceiver(
            String instance, @Nullable ScheduledExecutorService scheduler, Receiver receiver, LogThrottle drops) {
        return networks.service(instance)
                .scheduler(scheduler)
                .receiver(receiver)
                .drops(drops)
                .build();
    }

    @Test
    void aMessageOverTheSizeCapIsDroppedBeforeItIsQueued() {
        Receiver receiver = new Receiver();
        try {
            NetworkService a = withReceiver("p3", null, receiver, new LogThrottle());
            a.start();
            int queued = receiver.queued.get();
            broker.transport()
                    .publish(
                            TestNetworks.channel(NetworkSettings.ALL),
                            "x".repeat(EnvelopeCodec.MAX_BYTES - 1) + "\u00e9");
            broker.transport().publish(TestNetworks.channel("p3"), "y".repeat(EnvelopeCodec.MAX_BYTES + 1));
            assertEquals(queued, receiver.queued.get());
            broker.transport().publish(TestNetworks.channel("p3"), "z".repeat(EnvelopeCodec.MAX_BYTES));
            assertEquals(queued + 1, receiver.queued.get());
            a.stop();
        } finally {
            receiver.shutdownNow();
        }
    }

    @Test
    void aFullReceiveQueueDropsWarnsOnceAWindowAndTheScheduledRosterUpkeepKeepsRunning() throws Exception {
        Receiver receiver = new Receiver();
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong nanos = new AtomicLong();
        CountDownLatch release = new CountDownLatch(1);
        try (CapturedLog log = new CapturedLog(Level.WARNING, "Dropped a received network message")) {
            NetworkService a =
                    withReceiver("p3", scheduler, receiver, new LogThrottle(TimeUnit.MINUTES.toNanos(1), nanos::get));
            a.start();
            NetworkService b = started("p7", new Players().with(ALICE, "Alice", "lobby"));
            await(() -> a.locate("Alice").isPresent());
            a.on("probe", envelope -> {
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

            long late = RosterCache.EXPIRY_MILLIS + 1;
            clock.addAndGet(late);
            broker.published.clear();
            scheduler.fire();
            assertEquals(1, rosterBodiesFrom("p3").size());
            clock.addAndGet(-late);
            assertTrue(a.locate("Alice").isEmpty(), "an instance the upkeep forgot stays gone with the clock back");
        } finally {
            release.countDown();
            receiver.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void aForwardedCommandNothingAnswersFailsOnTheSchedulerWhileTheReceiverIsBusy() throws Exception {
        Receiver receiver = new Receiver();
        ManualScheduler scheduler = new ManualScheduler();
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            NetworkService a = networks.service("p3")
                    .scheduler(scheduler)
                    .receiver(receiver)
                    .commands(commands(new ArrayList<>()))
                    .build();
            a.on("probe", envelope -> {
                busy.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            a.start();
            started("p7", new Players()).send("p3", "probe", new JsonObject());
            assertTrue(busy.await(10, TimeUnit.SECONDS));
            Sender sender = mock(Sender.class);
            when(sender.getUniqueId()).thenReturn(UUID.randomUUID());
            when(sender.getName()).thenReturn("Mod");

            CompletableFuture<Boolean> result =
                    a.run(sender, "cult profile Alice", new NetworkPlayer(ALICE, "Alice", "p9", "lobby"));
            clock.addAndGet(RemoteCommands.ANSWER_MILLIS);
            scheduler.fire();
            assertFalse(result.getNow(true));
        } finally {
            release.countDown();
            receiver.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void theFirstBadSignatureWarnsAndTheNextAreThrottled() {
        try (CapturedLog log = new CapturedLog(Level.WARNING, "Dropped a network message with a bad signature")) {
            started("p3", new Players());
            MessageTransport bus = broker.transport();
            MessageTransport intoOurChannels = new MessageTransport() {
                @Override
                public String name() {
                    return "into our channels";
                }

                @Override
                public void start(List<String> channels, MessageTransport.Receiver receiver) {
                    bus.start(channels, receiver);
                }

                @Override
                public void publish(String channel, String text) {
                    bus.publish(TestNetworks.channel(channel.substring(channel.lastIndexOf('.') + 1)), text);
                }

                @Override
                public void close() {
                    bus.close();
                }
            };
            NetworkService stranger = builder("p9", new Players().with(ALICE, "Alice", "lobby"), null, intoOurChannels)
                    .key("a different secret".getBytes(StandardCharsets.UTF_8))
                    .build();
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
        older.on("probe", envelope -> heard.add("older " + envelope.body().get("n")));
        newer.on("probe", envelope -> heard.add("newer " + envelope.body().get("n")));

        JsonObject body = new JsonObject();
        body.addProperty("n", 1);
        assertTrue(newer.sendLocal("probe", body));
        assertEquals(List.of("older 1", "newer 1"), heard);

        heard.clear();
        older.stop();
        body.addProperty("n", 2);
        assertTrue(newer.send("p3", "probe", body));
        assertEquals(List.of("newer 2"), heard);
        assertFalse(newer.broadcast("probe", body));
        assertFalse(newer.enabled());
        assertTrue(broker.published.isEmpty());
    }

    @Test
    void withoutAHostTheBusReachesOnlyThisGeneration() {
        NetworkService alone = service("p3", new Players(), null, null, false);
        alone.start();
        List<String> heard = new ArrayList<>();
        alone.on("probe", envelope -> heard.add(envelope.from()));
        assertTrue(alone.sendLocal("probe", new JsonObject()));
        assertEquals(List.of("p3"), heard);
        alone.stop();
        assertFalse(alone.sendLocal("probe", new JsonObject()));
    }

    @Test
    void messagesForThisInstanceNeverReachTheBroker() {
        NetworkService a = started("p3", new Players());
        List<String> heard = new ArrayList<>();
        a.on("probe", envelope -> heard.add(envelope.from()));
        broker.published.clear();

        assertTrue(a.send("p3", "probe", new JsonObject()));
        assertEquals(List.of("p3"), heard);
        assertTrue(broker.published.isEmpty());
    }

    @Test
    void anInstanceHearsWhatIsSentToItOrToAllButNotItsOwnBroadcast() {
        NetworkService a = started("p3", new Players());
        NetworkService b = started("p7", new Players());
        List<String> heard = new ArrayList<>();
        a.on("probe", envelope -> heard.add((envelope.broadcast() ? "all" : "p3") + " from " + envelope.from()));
        b.on("probe", envelope -> heard.add("b heard its own"));

        assertTrue(b.send("p3", "probe", new JsonObject()));
        assertTrue(b.broadcast("probe", new JsonObject()));
        assertEquals(List.of("p3 from p7", "all from p7"), heard);
    }

    @Test
    void aTypeWithoutAHandlerIsDroppedAndARemovedHandlerHearsNothing() {
        NetworkService a = started("p3", new Players());
        NetworkService b = started("p7", new Players());
        List<String> heard = new ArrayList<>();
        NetworkService.Registration registration = a.on("probe", envelope -> heard.add("probe"));
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
        assertFalse(a.peersWantVerbose());
    }

    @Test
    void whetherStaffTakeVerboseReachesThePeersWithTheNextDeltaAndEachSnapshot() {
        NetworkService a = started("p3", new Players());
        NetworkService b = started("p7", new Players());
        AtomicBoolean wanted = new AtomicBoolean();
        b.verbose(wanted::get);
        int published = rosterBodiesFrom("p7").size();
        b.verboseChanged();
        b.sendDelta();
        assertEquals(published, rosterBodiesFrom("p7").size());

        wanted.set(true);
        b.verboseChanged();
        assertFalse(a.peersWantVerbose());
        b.sendDelta();
        assertEquals(published + 1, rosterBodiesFrom("p7").size());
        assertTrue(a.peersWantVerbose());
        assertTrue(rosterBodiesFrom("p7").getLast().get("verbose").getAsBoolean());
        b.tick();
        assertTrue(a.peersWantVerbose());

        wanted.set(false);
        b.tick();
        assertFalse(a.peersWantVerbose());
        clock.addAndGet(RosterCache.EXPIRY_MILLIS + 1);
        wanted.set(true);
        b.verboseChanged();
        b.sendDelta();
        clock.addAndGet(RosterCache.EXPIRY_MILLIS + 1);
        assertFalse(a.peersWantVerbose());
    }

    @Test
    void aVerboseChangeReportedWhileADeltaIsTakenRidesTheNextOne() {
        NetworkService a = started("p3", new Players());
        NetworkService b = started("p7", new Players());
        AtomicBoolean wanted = new AtomicBoolean();
        AtomicBoolean reportOnRead = new AtomicBoolean(true);
        b.verbose(() -> {
            boolean now = wanted.get();
            if (reportOnRead.getAndSet(false)) {
                wanted.set(true);
                b.verboseChanged();
            }
            return now;
        });
        b.playerChanged(new OnlinePlayer(ALICE, "Alice", "lobby"));
        b.sendDelta();
        assertFalse(a.peersWantVerbose());

        b.sendDelta();
        assertTrue(a.peersWantVerbose());
    }

    @Test
    void aVerboseChangeRidesTheNextDeltaWithThePlayers() {
        NetworkService a = started("p3", new Players());
        NetworkService b = started("p7", new Players());
        b.verbose(() -> true);
        int published = rosterBodiesFrom("p7").size();

        b.verboseChanged();
        b.playerChanged(new OnlinePlayer(ALICE, "Alice", "lobby"));
        b.sendDelta();

        assertEquals(published + 1, rosterBodiesFrom("p7").size());
        JsonObject delta = rosterBodiesFrom("p7").getLast();
        assertFalse(delta.get("full").getAsBoolean());
        assertTrue(delta.get("verbose").getAsBoolean());
        assertTrue(a.peersWantVerbose());
        assertEquals("p7", a.locate("Alice").orElseThrow().instance());
    }

    @Test
    void aStoppedGenerationDropsWhatItWasStillToSend() {
        started("p3", new Players());
        NetworkService b = started("p7", new Players());
        b.playerChanged(new OnlinePlayer(ALICE, "Alice", "lobby"));
        b.stop();
        int published = rosterBodiesFrom("p7").size();
        b.sendDelta();
        b.playerChanged(new OnlinePlayer(BOB, "Bob", "lobby"));
        b.sendDelta();
        assertEquals(published, rosterBodiesFrom("p7").size());
    }

    @Test
    void aRosterAboveTheOldCapStillReachesThePeers() {
        Players crowded = new Players();
        for (int i = 0; i < 8_000; i++) crowded.with(new UUID(7, i), "Player" + i, "server" + i % 8);
        NetworkService a = started("p3", new Players());
        try (CapturedLog log = new CapturedLog(Level.WARNING, "Network roster not sent")) {
            started("p7", crowded);
            String text = broker.on(TestNetworks.channel(NetworkSettings.ALL))
                    .getLast()
                    .text();
            assertTrue(EnvelopeCodec.utf8Length(text) > 256 * 1024);
            assertEquals(8_000, a.roster().size());
            assertEquals("server3", a.locate("Player3").orElseThrow().server());
            assertTrue(log.messages().isEmpty());
        }
    }

    @Test
    void aRosterThatStillDoesNotFitIsLoggedOnce() {
        Players crowded = new Players();
        for (int i = 0; i < 20_000; i++) crowded.with(new UUID(7, i), "Player" + i, "lobby");
        NetworkService a = started("p3", new Players());
        try (CapturedLog log = new CapturedLog(Level.WARNING, "Network roster not sent")) {
            NetworkService b = started("p7", crowded);
            b.tick();
            assertEquals(1, log.messages().size());
            assertTrue(
                    log.messages().getFirst().contains("20000 players"),
                    log.messages().getFirst());
            assertTrue(a.roster().isEmpty());
        }
    }

    @Test
    void aRosterThatFitsOnlyWithoutBrandsReachesThePeersWithoutThemAndIsLoggedOnce() {
        Players crowded = new Players();
        for (int i = 0; i < 8_000; i++) {
            String brand = ("client " + i + " ").repeat(16).substring(0, 64);
            crowded.with(new UUID(7, i), "Player" + i, "server" + i % 8, brand);
        }
        NetworkService a = started("p3", new Players());
        try (CapturedLog sent = new CapturedLog(Level.WARNING, "Network roster sent without client brands");
                CapturedLog dropped = new CapturedLog(Level.WARNING, "Network roster not sent")) {
            NetworkService b = started("p7", crowded);
            b.tick();
            assertEquals(8_000, a.roster().size());
            assertEquals("server3", a.locate("Player3").orElseThrow().server());
            assertTrue(a.roster().stream().allMatch(player -> player.brand() == null));
            assertFalse(rosterBodiesFrom("p7").getLast().has("brands"));
            assertEquals(1, sent.messages().size());
            assertTrue(
                    sent.messages().getFirst().contains("8000 players"),
                    sent.messages().getFirst());
            assertTrue(dropped.messages().isEmpty());
        }
    }

    @Test
    void aReportOnAnOlderGenerationReachesThePeersInTheCurrentOnesNextDelta() {
        Map<String, Object> shared = new HashMap<>();
        AtomicBoolean olderCurrent = new AtomicBoolean(true);
        AtomicBoolean newerCurrent = new AtomicBoolean(false);
        Players here = new Players().with(BOB, "Bob", "lobby").with(ALICE, "Alice", "lobby");
        NetworkService older = service("p3", here, host(shared, olderCurrent, "g1"), KEY, true);
        older.start();
        NetworkService peer = started("p7", new Players());
        olderCurrent.set(false);
        newerCurrent.set(true);
        NetworkService newer = service("p3", here, host(shared, newerCurrent, "g2"), KEY, true);
        newer.start();
        assertEquals("lobby", peer.locate("Bob").orElseThrow().server());

        here.with(BOB, "Bob", "survival", "fabric");
        older.playerChanged(new OnlinePlayer(BOB, "Bob", "survival", "fabric"));
        here.online.remove(ALICE);
        older.playerLeft(ALICE);
        int published = rosterBodiesFrom("p3").size();
        older.sendDelta();
        assertEquals(published, rosterBodiesFrom("p3").size());
        newer.sendDelta();
        assertEquals(published + 1, rosterBodiesFrom("p3").size());
        assertEquals(
                new NetworkPlayer(BOB, "Bob", "p3", "survival", "fabric"),
                peer.locate("Bob").orElseThrow());
        assertTrue(peer.locate("Alice").isEmpty());
        newer.sendDelta();
        assertEquals(published + 1, rosterBodiesFrom("p3").size());
    }

    /** An older and a newer, current generation of p3 sharing one host, and a peer p7 that heard the newer one. */
    private record Swapped(NetworkService older, NetworkService newer, NetworkService peer) {}

    private Swapped swapped(Players here) {
        Map<String, Object> shared = new HashMap<>();
        AtomicBoolean olderCurrent = new AtomicBoolean(true);
        AtomicBoolean newerCurrent = new AtomicBoolean(false);
        NetworkService older = service("p3", here, host(shared, olderCurrent, "g1"), KEY, true);
        older.start();
        NetworkService peer = started("p7", new Players());
        olderCurrent.set(false);
        newerCurrent.set(true);
        NetworkService newer = service("p3", here, host(shared, newerCurrent, "g2"), KEY, true);
        newer.start();
        return new Swapped(older, newer, peer);
    }

    @Test
    void theLaterOfTwoReportsForOnePlayerWinsWhicheverGenerationMadeThem() {
        Swapped swapped = swapped(new Players().with(BOB, "Bob", "lobby"));

        swapped.newer().playerChanged(new OnlinePlayer(BOB, "Bob", "survival"));
        swapped.older().playerChanged(new OnlinePlayer(BOB, "Bob", "hub"));
        swapped.newer().sendDelta();
        assertEquals("hub", swapped.peer().locate("Bob").orElseThrow().server());

        swapped.older().playerChanged(new OnlinePlayer(BOB, "Bob", "lobby"));
        swapped.newer().playerChanged(new OnlinePlayer(BOB, "Bob", "survival"));
        swapped.newer().sendDelta();
        assertEquals("survival", swapped.peer().locate("Bob").orElseThrow().server());
    }

    @Test
    void anOlderGenerationsTickAndStopKeepItsReportsForTheCurrentOne() {
        Players here = new Players().with(BOB, "Bob", "lobby").with(ALICE, "Alice", "lobby");
        Swapped swapped = swapped(here);
        int published = rosterBodiesFrom("p3").size();

        here.with(BOB, "Bob", "survival");
        swapped.older().playerChanged(new OnlinePlayer(BOB, "Bob", "survival"));
        swapped.older().tick();
        here.online.remove(ALICE);
        swapped.older().playerLeft(ALICE);
        swapped.older().stop();
        assertEquals(published, rosterBodiesFrom("p3").size());

        swapped.newer().sendDelta();
        assertEquals(published + 1, rosterBodiesFrom("p3").size());
        assertFalse(rosterBodiesFrom("p3").getLast().get("full").getAsBoolean());
        assertEquals("survival", swapped.peer().locate("Bob").orElseThrow().server());
        assertTrue(swapped.peer().locate("Alice").isEmpty());
    }

    @Test
    void aReportMadeAfterADrainRidesTheNextDelta() {
        Players here = new Players().with(BOB, "Bob", "lobby").with(ALICE, "Alice", "lobby");
        Swapped swapped = swapped(here);

        swapped.older().playerChanged(new OnlinePlayer(BOB, "Bob", "lobby"));
        swapped.newer().tick();
        int published = rosterBodiesFrom("p3").size();
        swapped.newer().sendDelta();
        assertEquals(published, rosterBodiesFrom("p3").size());

        here.online.remove(ALICE);
        swapped.older().playerLeft(ALICE);
        swapped.newer().sendDelta();
        assertEquals(published + 1, rosterBodiesFrom("p3").size());
        assertTrue(swapped.peer().locate("Alice").isEmpty());

        swapped.older().playerChanged(new OnlinePlayer(BOB, "Bob", "survival"));
        swapped.newer().sendDelta();
        assertEquals(published + 2, rosterBodiesFrom("p3").size());
        assertEquals("survival", swapped.peer().locate("Bob").orElseThrow().server());
    }

    @Test
    void aClockOutsideTheWindowWarnsOncePerPeerAWhile() {
        AtomicLong skewed = new AtomicLong(clock.get() + EnvelopeCodec.MAX_SKEW_MILLIS + 5_000);
        NetworkService a = started("p3", new Players());
        try (CapturedLog log = new CapturedLog(Level.WARNING, "Dropping network messages from p9")) {
            NetworkService late = new TestNetworks(broker, skewed)
                    .service("p9")
                    .platform(new Players().with(ALICE, "Alice", "lobby"))
                    .start();
            late.tick();
            assertTrue(a.locate("Alice").isEmpty());
            assertEquals(1, log.messages().size());
            assertTrue(
                    log.messages().getFirst().contains("35000 ms"),
                    log.messages().getFirst());
        }
    }

    @Test
    void anotherJvmWithThisInstanceIdIsReportedOnce() {
        try (CapturedLog log = new CapturedLog(Level.WARNING, "Another instance uses this instance's id p3")) {
            NetworkService first =
                    service("p3", new Players(), host(new HashMap<>(), new AtomicBoolean(true)), broker.transport());
            first.start();
            NetworkService generation =
                    service("p3", new Players(), host(new HashMap<>(), new AtomicBoolean(true)), broker.transport());
            first.tick();
            assertTrue(log.messages().isEmpty());
            generation.start();
            assertEquals(1, log.messages().size());
            first.tick();
            generation.tick();
            first.tick();
            assertEquals(2, log.messages().size(), "one from each side");
        }
    }

    @Test
    void generationsOfOneJvmShareTheirNonceAndRaiseNoWarning() {
        Map<String, Object> shared = new ConcurrentHashMap<>();
        try (CapturedLog log = new CapturedLog(Level.WARNING, "Another instance uses this instance's id")) {
            NetworkService older =
                    service("p3", new Players(), host(shared, new AtomicBoolean(true)), broker.transport());
            older.start();
            NetworkService newer =
                    service("p3", new Players(), host(shared, new AtomicBoolean(true)), broker.transport());
            newer.start();
            older.tick();
            newer.tick();
            assertTrue(log.messages().isEmpty());
        }
    }

    @Test
    void runRunsALocalPlayersCommandThroughTheCommandManagerAndForwardsARemoteOne() {
        List<String> ranHere = new ArrayList<>();
        List<String> ranThere = new ArrayList<>();
        NetworkService a = networks.service("p3")
                .platform(new Players().with(BOB, "Bob", null))
                .commands(commands(ranHere))
                .start();
        NetworkService b = networks.service("p7")
                .platform(new Players().with(ALICE, "Alice", "lobby"))
                .commands(commands(ranThere))
                .start();
        Sender sender = mock(Sender.class);
        when(sender.getUniqueId()).thenReturn(UUID.randomUUID());
        when(sender.getName()).thenReturn("Mod");

        assertTrue(
                a.run(sender, "cult profile Bob", a.locate("Bob").orElseThrow()).join());
        assertEquals(List.of("cult profile Bob"), ranHere);

        assertTrue(a.run(sender, "cult profile Alice", a.locate("Alice").orElseThrow())
                .join());
        assertEquals(List.of("cult profile Alice"), ranThere);
        verify(sender, never()).performCommand(anyString());
    }

    private static RemoteCommands.Commands commands(List<String> ran) {
        return new RemoteCommands.Commands() {
            @Override
            public CompletionStage<?> execute(Sender sender, String line) {
                ran.add(line);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public Set<String> permissionNodes() {
                return Set.of();
            }
        };
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
