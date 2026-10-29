package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.bridge.protocol.CommandRoots;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.command.IsolatedCommandManager;
import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.RoutingHolder;
import ac.cult.cultac.network.routing.NetworkPlatform.Holder;
import ac.cult.cultac.network.routing.NetworkPlatform.OnlinePlayer;
import ac.cult.cultac.network.routing.RemoteCommands.Commands;
import ac.cult.cultac.platform.api.command.PlayerSelector;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import ac.cult.reload.api.HostContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.incendo.cloud.parser.ArgumentParseResult;
import org.incendo.cloud.parser.ParserDescriptor;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemoteCommandsTest {
    private static final byte[] KEY = "a shared network secret of some length".getBytes(StandardCharsets.UTF_8);
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID MOD = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
    private static final NetworkSettings SETTINGS =
            new NetworkSettings("redis", "", "default", "", "", List.of(), "", "", "");
    private static final String LINE = "cult debug mods Alice";
    private static final Set<String> NODES =
            Set.of("cult.debug", "cult.consoledebug", "cult.cloud.view", "cult.profile");

    private final AtomicLong clock = new AtomicLong(1_793_140_000_000L);
    private final FakeBroker broker = new FakeBroker();

    private PlainPlaceholders placeholders;

    @BeforeAll
    static void config() {
        OfflineCultTestBootstrap.installConfig();
    }

    @BeforeEach
    void placeholders() throws ReflectiveOperationException {
        placeholders = new PlainPlaceholders();
    }

    @AfterEach
    void restorePlaceholders() throws ReflectiveOperationException {
        placeholders.close();
    }

    /** The players a platform reports online, and which generation holds each. */
    private static final class Players implements NetworkPlatform {
        final Map<UUID, OnlinePlayer> online = new ConcurrentHashMap<>();
        final Map<UUID, Holder> holders = new ConcurrentHashMap<>();

        Players with(UUID uuid, String name) {
            online.put(uuid, new OnlinePlayer(uuid, name, "lobby"));
            return this;
        }

        @Override
        public Collection<OnlinePlayer> onlinePlayers() {
            return List.copyOf(online.values());
        }

        @Override
        public Holder holder(UUID uuid) {
            return holders.getOrDefault(uuid, Holder.THIS);
        }

        @Override
        public String address() {
            return "test:1";
        }
    }

    /** A command manager recording what it ran; by default each command answers one line. */
    private static final class Recorder implements Commands {
        final List<String> lines = new CopyOnWriteArrayList<>();
        final List<Sender> senders = new CopyOnWriteArrayList<>();
        BiFunction<Sender, String, CompletionStage<?>> behaviour = (sender, line) -> {
            sender.sendMessage(Component.text("ran " + line));
            return CompletableFuture.completedFuture(null);
        };

        @Override
        public CompletionStage<?> execute(Sender sender, String line) {
            lines.add(line);
            senders.add(sender);
            return behaviour.apply(sender, line);
        }

        @Override
        public Set<String> permissionNodes() {
            return NODES;
        }
    }

    /** A staff member running commands, holding {@code permissions}. */
    private static final class Staff implements Sender {
        final List<Component> heard = new CopyOnWriteArrayList<>();
        final boolean console;
        final Set<String> permissions;

        Staff(boolean console, Set<String> permissions) {
            this.console = console;
            this.permissions = permissions;
        }

        List<String> plain() {
            return heard.stream()
                    .map(PlainTextComponentSerializer.plainText()::serialize)
                    .toList();
        }

        @Override
        public String getName() {
            return console ? CONSOLE_NAME : "Mod";
        }

        @Override
        public UUID getUniqueId() {
            return console ? CONSOLE_UUID : MOD;
        }

        @Override
        public void sendMessage(String message) {
            heard.add(Component.text(message));
        }

        @Override
        public void sendMessage(Component message) {
            heard.add(message);
        }

        @Override
        public boolean hasPermission(String permission) {
            return permissions.contains(permission);
        }

        @Override
        public boolean hasPermission(String permission, boolean defaultIfUnset) {
            return permissions.contains(permission);
        }

        @Override
        public void performCommand(String commandLine) {
            throw new AssertionError("commands run through the command manager");
        }

        @Override
        public boolean isConsole() {
            return console;
        }

        @Override
        public @NotNull Object getNativeSender() {
            return this;
        }

        @Override
        public @Nullable PlatformPlayer getPlatformPlayer() {
            return null;
        }
    }

    private final Staff staff = new Staff(false, Set.of("cult.debug", "cult.cloud.view", "cult.admin"));

    private NetworkService service(
            String instance, Players players, @Nullable HostContext host, boolean transport, Recorder commands) {
        NetworkService service = new NetworkService(
                SETTINGS,
                players,
                host,
                instance,
                KEY,
                transport ? broker.transport() : null,
                clock::get,
                null,
                null,
                new LogThrottle());
        service.commands(commands);
        return service;
    }

    private NetworkService started(String instance, Players players, Recorder commands) {
        NetworkService service = service(instance, players, null, true, commands);
        service.start();
        return service;
    }

    @SuppressWarnings("unchecked")
    private static HostContext host(Map<String, Object> shared, boolean current) {
        HostContext host = mock(HostContext.class);
        when(host.current()).thenReturn(current);
        when(host.shared(anyString(), any()))
                .thenAnswer(invocation -> shared.computeIfAbsent(
                        invocation.getArgument(0), key -> ((Supplier<Object>) invocation.getArgument(1)).get()));
        return host;
    }

    private static NetworkPlayer aliceOn(String instance) {
        return new NetworkPlayer(ALICE, "Alice", instance, "lobby");
    }

    /** The bodies of the {@code cmd} messages {@code service} receives. */
    private static List<JsonObject> requests(NetworkService service) {
        List<JsonObject> bodies = new CopyOnWriteArrayList<>();
        service.on(RemoteCommands.CMD, (envelope, origin) -> bodies.add(envelope.body()));
        return bodies;
    }

    private static JsonObject output(String id, int seq, Component component) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        body.addProperty("seq", seq);
        body.add(
                "component",
                JsonParser.parseString(GsonComponentSerializer.gson().serialize(component)));
        return body;
    }

    private static JsonObject done(String id, boolean ok, int count) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        body.addProperty("ok", ok);
        body.addProperty("count", count);
        return body;
    }

    /** The envelopes published on {@code channel} as {@code type}. */
    private List<JsonObject> envelopes(String channel, String type) {
        List<JsonObject> envelopes = new ArrayList<>();
        for (FakeBroker.Published message : broker.on(channel)) {
            String text = message.text();
            JsonObject envelope = JsonParser.parseString(text.substring(text.indexOf('.') + 1))
                    .getAsJsonObject();
            if (envelope.get("type").getAsString().equals(type)) envelopes.add(envelope);
        }
        return envelopes;
    }

    @Test
    void aForwardedCommandRunsWhereThePlayerIsAndItsOutputComesBackInOrderWithItsClicks() {
        Recorder here = new Recorder();
        NetworkService a = started("p3", new Players(), here);
        Recorder there = new Recorder();
        Component clickable = Component.text("copy", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.copyToClipboard("abc"))
                .hoverEvent(HoverEvent.showText(Component.text("hover")));
        there.behaviour = (sender, line) -> {
            sender.sendMessage(Component.text("first"));
            sender.sendMessage("second");
            sender.sendMessage(clickable);
            return CompletableFuture.completedFuture(null);
        };
        started("p7", new Players().with(ALICE, "Alice"), there);

        NetworkPlayer alice = a.locate("Alice").orElseThrow();
        assertEquals("p7", alice.instance());
        assertTrue(a.run(staff, LINE, alice).join());

        assertTrue(here.lines.isEmpty());
        assertEquals(List.of(LINE), there.lines);
        RemoteCommandSource source = assertInstanceOf(RemoteCommandSource.class, there.senders.getFirst());
        assertEquals("Mod", source.getName());
        assertEquals(MOD, source.getUniqueId());
        assertFalse(source.isConsole());
        assertFalse(source.isPlayer());
        assertEquals(List.of("first", "second", "copy"), staff.plain());
        assertEquals(clickable, staff.heard.get(2));
    }

    @Test
    void theReceivingInstanceRunsTheLineUnderItsOwnRoot() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Recorder there = new Recorder();
        started("p7", new Players().with(ALICE, "Alice"), there);
        assertTrue(a.run(staff, "guard debug mods Alice", aliceOn("p7")).join());
        assertEquals(List.of("cult debug mods Alice"), there.lines);
    }

    @Test
    void aPlayerWhoLeftBeforeTheCommandArrivedIsReportedGone() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Players players = new Players().with(ALICE, "Alice");
        Recorder there = new Recorder();
        started("p7", players, there);
        players.online.remove(ALICE);

        assertFalse(a.run(staff, LINE, aliceOn("p7")).join());
        assertTrue(there.lines.isEmpty());
        assertEquals(List.of("Alice is no longer on p7."), staff.plain());
    }

    @Test
    void aCommandThatFoundItsPlayerGoneReportsThatTheyLeft() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Recorder there = new Recorder();
        there.behaviour = (sender, line) -> {
            ((RemoteCommandSource) sender).targetLeft();
            return CompletableFuture.completedFuture(null);
        };
        started("p7", new Players().with(ALICE, "Alice"), there);

        assertFalse(a.run(staff, LINE, aliceOn("p7")).join());
        assertEquals(List.of("Alice is no longer on p7."), staff.plain());
    }

    @Test
    void aCommandThatFailedThereStillRanAndItsOwnErrorsReachTheSender() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Recorder there = new Recorder();
        there.behaviour = (sender, line) -> {
            sender.sendMessage(Component.text("Invalid command syntax."));
            return CompletableFuture.failedFuture(new IllegalArgumentException("syntax"));
        };
        started("p7", new Players().with(ALICE, "Alice"), there);

        assertTrue(a.run(staff, LINE, aliceOn("p7")).join());
        assertEquals(List.of("Invalid command syntax."), staff.plain());
    }

    @Test
    void anInstanceThatNeverAnswersFailsAfterTenSeconds() {
        NetworkService a = started("p3", new Players(), new Recorder());
        NetworkService b = started("p7", new Players().with(ALICE, "Alice"), new Recorder());
        b.stop();

        CompletableFuture<Boolean> ran = a.run(staff, LINE, aliceOn("p7"));
        clock.addAndGet(RemoteCommands.ANSWER_MILLIS - 1);
        a.sweepCommands();
        assertFalse(ran.isDone());
        clock.addAndGet(1);
        a.sweepCommands();
        assertFalse(ran.join());
        assertEquals(List.of("p7 did not answer."), staff.plain());
    }

    @Test
    void stoppingEndsTheRequestsStillAwaitingAnAnswer() {
        NetworkService a = started("p3", new Players(), new Recorder());
        NetworkService b = started("p7", new Players().with(ALICE, "Alice"), new Recorder());
        b.stop();

        CompletableFuture<Boolean> ran = a.run(staff, LINE, aliceOn("p7"));
        a.stop();
        assertFalse(ran.join());
        assertEquals(List.of("p7 did not answer."), staff.plain());
    }

    @Test
    void outputWithoutAnAnswerCountsAsRunOnceItWentQuietForSixtySecondsWithANote() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Recorder there = new Recorder();
        AtomicReference<Sender> source = new AtomicReference<>();
        there.behaviour = (sender, line) -> {
            source.set(sender);
            sender.sendMessage(Component.text("working"));
            return new CompletableFuture<>();
        };
        started("p7", new Players().with(ALICE, "Alice"), there);

        CompletableFuture<Boolean> ran = a.run(staff, LINE, aliceOn("p7"));
        clock.addAndGet(RemoteCommands.ANSWER_MILLIS);
        a.sweepCommands();
        assertFalse(ran.isDone());
        clock.addAndGet(20_000);
        source.get().sendMessage(Component.text("still working"));
        clock.addAndGet(RemoteCommands.QUIET_MILLIS - 1);
        a.sweepCommands();
        assertFalse(ran.isDone());

        clock.addAndGet(1);
        a.sweepCommands();
        assertTrue(ran.join());
        List<String> heard = staff.plain();
        assertEquals(List.of("working", "still working"), heard.subList(0, 2));
        assertEquals(
                List.of("p7 did not report that the command finished; its output may be incomplete."),
                heard.subList(2, heard.size()));
        assertEquals(NamedTextColor.GRAY, staff.heard.getLast().color());
    }

    @Test
    void outputBeforeTheAnswerAndLateOutputWithinThirtySecondsOfItAreAllRelayed() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Recorder there = new Recorder();
        AtomicReference<Sender> source = new AtomicReference<>();
        CompletableFuture<Void> command = new CompletableFuture<>();
        there.behaviour = (sender, line) -> {
            source.set(sender);
            return command;
        };
        started("p7", new Players().with(ALICE, "Alice"), there);

        CompletableFuture<Boolean> ran = a.run(staff, LINE, aliceOn("p7"));
        clock.addAndGet(1_000);
        source.get().sendMessage(Component.text("one"));
        clock.addAndGet(10_000);
        a.sweepCommands();
        assertFalse(ran.isDone());
        clock.addAndGet(9_000);
        command.complete(null);
        assertTrue(ran.join());
        clock.addAndGet(25_000);
        a.sweepCommands();
        source.get().sendMessage(Component.text("late"));
        assertEquals(List.of("one", "late"), staff.plain());
    }

    @Test
    void outputArrivingAfterTheAnswerBehindItIsStillRelayedFirst() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Recorder there = new Recorder();
        there.behaviour = (sender, line) -> new CompletableFuture<>();
        NetworkService b = started("p7", new Players().with(ALICE, "Alice"), there);
        List<JsonObject> requests = requests(b);

        CompletableFuture<Boolean> ran = a.run(staff, LINE, aliceOn("p7"));
        String id = requests.getFirst().get("id").getAsString();
        b.send("p3", RemoteCommands.OUT, output(id, 1, Component.text("second")));
        b.send("p3", RemoteCommands.DONE, done(id, true, 2));
        assertTrue(ran.join());
        assertTrue(staff.plain().isEmpty());
        b.send("p3", RemoteCommands.OUT, output(id, 0, Component.text("first")));
        assertEquals(List.of("first", "second"), staff.plain());
    }

    @Test
    void outputTheAnswerCountedButThatNeverArrivedIsReportedLost() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Recorder there = new Recorder();
        there.behaviour = (sender, line) -> new CompletableFuture<>();
        NetworkService b = started("p7", new Players().with(ALICE, "Alice"), there);
        List<JsonObject> requests = requests(b);

        CompletableFuture<Boolean> ran = a.run(staff, LINE, aliceOn("p7"));
        String id = requests.getFirst().get("id").getAsString();
        b.send("p3", RemoteCommands.OUT, output(id, 0, Component.text("first")));
        b.send("p3", RemoteCommands.DONE, done(id, true, 2));
        assertTrue(ran.join());
        clock.addAndGet(RemoteCommands.LINGER_MILLIS + 1);
        a.sweepCommands();
        assertEquals(List.of("first", "Some output from p7 was lost."), staff.plain());
    }

    @Test
    void lateOutputReachesTheSenderForThirtySecondsAfterTheCommandCompleted() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Recorder there = new Recorder();
        AtomicReference<Sender> source = new AtomicReference<>();
        there.behaviour = (sender, line) -> {
            source.set(sender);
            return CompletableFuture.completedFuture(null);
        };
        NetworkService b = started("p7", new Players().with(ALICE, "Alice"), there);
        List<JsonObject> requests = requests(b);

        assertTrue(a.run(staff, LINE, aliceOn("p7")).join());
        clock.addAndGet(RemoteCommands.LINGER_MILLIS);
        source.get().sendMessage(Component.text("late"));
        assertEquals(List.of("late"), staff.plain());

        clock.addAndGet(1);
        String id = requests.getFirst().get("id").getAsString();
        b.send("p3", RemoteCommands.OUT, output(id, 1, Component.text("too late")));
        source.get().sendMessage(Component.text("also too late"));
        assertFalse(source.get().isValid());
        assertEquals(List.of("late"), staff.plain());
    }

    @Test
    void outputOutOfOrderIsRelayedInOrderAndAGapLeftWhenTheLateWindowClosesIsReported() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Recorder there = new Recorder();
        there.behaviour = (sender, line) -> new CompletableFuture<>();
        NetworkService b = started("p7", new Players().with(ALICE, "Alice"), there);
        List<JsonObject> requests = requests(b);

        CompletableFuture<Boolean> ran = a.run(staff, LINE, aliceOn("p7"));
        String id = requests.getFirst().get("id").getAsString();
        b.send("p3", RemoteCommands.OUT, output(id, 1, Component.text("second")));
        assertTrue(staff.plain().isEmpty());
        b.send("p3", RemoteCommands.OUT, output(id, 0, Component.text("first")));
        assertEquals(List.of("first", "second"), staff.plain());

        b.send("p3", RemoteCommands.OUT, output(id, 3, Component.text("fourth")));
        b.send("p3", RemoteCommands.DONE, done(id, true, 4));
        assertTrue(ran.join());
        b.send("p3", RemoteCommands.OUT, output(id, 0, Component.text("again")));
        clock.addAndGet(RemoteCommands.LINGER_MILLIS);
        a.sweepCommands();
        assertEquals(List.of("first", "second"), staff.plain());

        clock.addAndGet(1);
        a.sweepCommands();
        assertEquals(List.of("first", "second", "fourth", "Some output from p7 was lost."), staff.plain());
        b.send("p3", RemoteCommands.OUT, output(id, 2, Component.text("third")));
        assertEquals(4, staff.heard.size());
    }

    @Test
    void relayedOutputStopsAtTwoHundredComponentsAndSaysItWasCut() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Recorder there = new Recorder();
        there.behaviour = (sender, line) -> {
            for (int i = 0; i < 250; i++) sender.sendMessage(Component.text("line " + i));
            return CompletableFuture.completedFuture(null);
        };
        started("p7", new Players().with(ALICE, "Alice"), there);
        broker.published.clear();

        assertTrue(a.run(staff, LINE, aliceOn("p7")).join());
        List<String> heard = staff.plain();
        assertEquals(RemoteCommands.MAX_COMPONENTS + 1, heard.size());
        assertEquals("line 199", heard.get(RemoteCommands.MAX_COMPONENTS - 1));
        assertTrue(heard.getLast().contains("was cut"), heard.getLast());
        assertEquals(
                RemoteCommands.MAX_COMPONENTS,
                envelopes("cultac.default.p3", RemoteCommands.OUT).size());
        JsonObject done =
                envelopes("cultac.default.p3", RemoteCommands.DONE).getFirst().getAsJsonObject("body");
        assertEquals(RemoteCommands.MAX_COMPONENTS, done.get("count").getAsInt());
        assertTrue(done.get("cut").getAsBoolean());
    }

    @Test
    void theSenderKeepsItsOwnCapOnWhatAnInstanceSends() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Recorder there = new Recorder();
        there.behaviour = (sender, line) -> new CompletableFuture<>();
        NetworkService b = started("p7", new Players().with(ALICE, "Alice"), there);
        List<JsonObject> requests = requests(b);

        a.run(staff, LINE, aliceOn("p7"));
        String id = requests.getFirst().get("id").getAsString();
        for (int i = 0; i <= RemoteCommands.MAX_COMPONENTS + 5; i++)
            b.send("p3", RemoteCommands.OUT, output(id, i, Component.text("line " + i)));
        List<String> heard = staff.plain();
        assertEquals(RemoteCommands.MAX_COMPONENTS + 1, heard.size());
        assertTrue(heard.getLast().contains("was cut"), heard.getLast());
    }

    @Test
    void aComponentTooLargeForOneMessageIsReplacedAndTheRestStillArrives() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Recorder there = new Recorder();
        there.behaviour = (sender, line) -> {
            sender.sendMessage(Component.text("x".repeat(EnvelopeCodec.MAX_BYTES)));
            sender.sendMessage(Component.text("after"));
            return CompletableFuture.completedFuture(null);
        };
        started("p7", new Players().with(ALICE, "Alice"), there);
        broker.published.clear();

        assertTrue(a.run(staff, LINE, aliceOn("p7")).join());
        assertEquals(List.of("One line of output was too large to relay.", "after"), staff.plain());
        List<Integer> seqs = new ArrayList<>();
        for (JsonObject out : envelopes("cultac.default.p3", RemoteCommands.OUT))
            seqs.add(out.getAsJsonObject("body").get("seq").getAsInt());
        assertEquals(List.of(0, 1), seqs);
    }

    @Test
    void relayedOutputStopsAtTwoHundredFiftySixKilobytes() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Component big = Component.text("x".repeat(20_000));
        int size = GsonComponentSerializer.gson().serialize(big).length();
        Recorder there = new Recorder();
        there.behaviour = (sender, line) -> {
            for (int i = 0; i < 40; i++) sender.sendMessage(big);
            return CompletableFuture.completedFuture(null);
        };
        started("p7", new Players().with(ALICE, "Alice"), there);

        assertTrue(a.run(staff, LINE, aliceOn("p7")).join());
        int fits = RemoteCommands.MAX_OUTPUT_BYTES / size;
        assertEquals(fits + 1, staff.heard.size());
        assertTrue(staff.plain().getLast().contains("was cut"));
    }

    @Test
    void theGenerationHoldingThePlayerAnswersAndElseOnlyTheCurrentOne() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Map<String, Object> shared = new HashMap<>();
        Players oldView = new Players().with(ALICE, "Alice");
        Players newView = new Players().with(ALICE, "Alice");
        Recorder oldCommands = new Recorder();
        Recorder newCommands = new Recorder();
        service("p7", oldView, host(shared, false), true, oldCommands).start();
        service("p7", newView, host(shared, true), true, newCommands).start();

        oldView.holders.put(ALICE, Holder.THIS);
        newView.holders.put(ALICE, Holder.OTHER);
        assertTrue(a.run(staff, LINE, aliceOn("p7")).join());
        assertEquals(1, oldCommands.lines.size());
        assertTrue(newCommands.lines.isEmpty());

        oldView.holders.put(ALICE, Holder.NONE);
        newView.holders.put(ALICE, Holder.NONE);
        assertTrue(a.run(staff, LINE, aliceOn("p7")).join());
        assertEquals(1, oldCommands.lines.size());
        assertEquals(1, newCommands.lines.size());

        oldView.holders.put(ALICE, Holder.OTHER);
        newView.holders.put(ALICE, Holder.THIS);
        assertTrue(a.run(staff, LINE, aliceOn("p7")).join());
        assertEquals(1, oldCommands.lines.size());
        assertEquals(2, newCommands.lines.size());
        assertEquals(List.of("ran " + LINE, "ran " + LINE, "ran " + LINE), staff.plain());
    }

    @Test
    void aRequestRunsOnceWhenThePlayerMovesToTheOtherGenerationBetweenTheirTurns() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Map<String, Object> shared = new HashMap<>();
        Players oldView = new Players().with(ALICE, "Alice");
        Players newView = new Players().with(ALICE, "Alice");
        oldView.holders.put(ALICE, Holder.THIS);
        newView.holders.put(ALICE, Holder.OTHER);
        Recorder oldCommands = new Recorder();
        Recorder newCommands = new Recorder();
        oldCommands.behaviour = (sender, line) -> {
            oldView.holders.put(ALICE, Holder.OTHER);
            newView.holders.put(ALICE, Holder.THIS);
            sender.sendMessage(Component.text("ran " + line));
            return CompletableFuture.completedFuture(null);
        };
        service("p7", oldView, host(shared, false), true, oldCommands).start();
        service("p7", newView, host(shared, true), true, newCommands).start();
        broker.published.clear();

        assertTrue(a.run(staff, LINE, aliceOn("p7")).join());
        assertEquals(List.of(LINE), oldCommands.lines);
        assertTrue(newCommands.lines.isEmpty());
        assertEquals(List.of("ran " + LINE), staff.plain());
        assertEquals(1, envelopes("cultac.default.p3", RemoteCommands.DONE).size());
    }

    @Test
    void claimedRequestIdsAreForgottenAfterTheReplayWindow() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Map<String, Object> shared = new HashMap<>();
        service("p7", new Players().with(ALICE, "Alice"), host(shared, true), true, new Recorder())
                .start();

        assertTrue(a.run(staff, LINE, aliceOn("p7")).join());
        Map<?, ?> claims = assertInstanceOf(Map.class, shared.get(RemoteCommands.CLAIMS));
        assertEquals(1, claims.size());
        clock.addAndGet(EnvelopeCodec.REPLAY_WINDOW_MILLIS);
        assertTrue(a.run(staff, LINE, aliceOn("p7")).join());
        assertEquals(2, claims.size());
        clock.addAndGet(SharedClaims.SWEEP_PERIOD_MILLIS);
        assertTrue(a.run(staff, LINE, aliceOn("p7")).join());
        assertEquals(2, claims.size());
    }

    @Test
    void stoppingWhileCommandsAreForwardedLeavesNoRequestWithoutAnAnswer() throws InterruptedException {
        for (int round = 0; round < 200; round++) {
            NetworkService a = started("p3", new Players(), new Recorder());
            Staff sender = new Staff(false, Set.of("cult.debug"));
            List<CompletableFuture<Boolean>> runs = new CopyOnWriteArrayList<>();
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch halfway = new CountDownLatch(1);
            Thread forwarding = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException interrupted) {
                    return;
                }
                for (int i = 0; i < 50; i++) {
                    runs.add(a.run(sender, LINE, aliceOn("p7")));
                    if (i == 24) halfway.countDown();
                }
            });
            forwarding.start();
            go.countDown();
            assertTrue(halfway.await(10, TimeUnit.SECONDS), "round " + round);
            a.stop();
            forwarding.join(10_000);
            assertFalse(forwarding.isAlive(), "round " + round);
            assertEquals(50, runs.size());
            for (CompletableFuture<Boolean> run : runs) {
                assertTrue(run.isDone(), "round " + round);
                assertFalse(run.join());
            }
            assertEquals(50, sender.heard.size());
        }
    }

    @Test
    void aPlayerAnotherGenerationHereHoldsIsReachedOverTheBusWithoutATransport() {
        Map<String, Object> shared = new HashMap<>();
        Players oldView = new Players().with(ALICE, "Alice");
        Players newView = new Players().with(ALICE, "Alice");
        oldView.holders.put(ALICE, Holder.THIS);
        newView.holders.put(ALICE, Holder.OTHER);
        Recorder oldCommands = new Recorder();
        Recorder newCommands = new Recorder();
        NetworkService draining = service("p3", oldView, host(shared, false), false, oldCommands);
        NetworkService current = service("p3", newView, host(shared, true), false, newCommands);
        draining.start();
        current.start();

        NetworkPlayer alice = current.locate("Alice").orElseThrow();
        assertEquals("p3", alice.instance());
        assertTrue(current.run(staff, LINE, alice).join());
        assertEquals(List.of(LINE), oldCommands.lines);
        assertInstanceOf(RemoteCommandSource.class, oldCommands.senders.getFirst());
        assertTrue(newCommands.lines.isEmpty());
        assertEquals(List.of("ran " + LINE), staff.plain());
        assertTrue(broker.published.isEmpty());
    }

    @Test
    void aPlayerThisGenerationHoldsRunsHereThroughTheCommandManager() {
        Players players = new Players().with(BOB, "Bob");
        Recorder here = new Recorder();
        NetworkService a = started("p3", players, here);
        broker.published.clear();

        NetworkPlayer bob = a.locate("Bob").orElseThrow();
        assertTrue(a.run(staff, "cult debug mods Bob", bob).join());
        assertEquals(List.of("cult debug mods Bob"), here.lines);
        assertSame(staff, here.senders.getFirst());
        assertTrue(broker.on("cultac.default.p3").isEmpty());

        players.online.remove(BOB);
        assertFalse(a.run(staff, "cult debug mods Bob", bob).join());
        assertEquals(1, here.lines.size());
        assertTrue(staff.plain().getLast().contains("Player is exempt or offline!"), staff.plain()::toString);
    }

    @Test
    void aForwardedCommandIsNeverForwardedAgain() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Recorder there = new Recorder();
        started("p7", new Players().with(ALICE, "Alice"), there);
        RemoteCommandSource forwarded = new RemoteCommandSource(
                MOD, "Mod", false, List.of("cult.debug"), NODES, component -> {}, clock::get, null);

        assertFalse(a.run(forwarded, LINE, aliceOn("p7")).join());
        assertTrue(forwarded.left());
        assertTrue(there.lines.isEmpty());
    }

    @Test
    void aForwardedCommandWhosePlayerParsesElsewhereReportsThemGoneInsteadOfForwarding() {
        NetworkService a = started("p3", new Players(), new Recorder());
        Recorder there = new Recorder();
        started("p7", new Players().with(ALICE, "Alice"), there);
        CloudCommandService commands =
                new CloudCommandService(IsolatedCommandManager::new, new Offline(), () -> CommandRoots.DEFAULT);
        commands.registerCommands();
        List<Component> heard = new ArrayList<>();
        RemoteCommandSource forwarded = new RemoteCommandSource(
                MOD, "Mod", false, List.of("cult.debug"), commands.permissionNodes(), heard::add, clock::get, null);
        RoutingHolder.install(a);
        try {
            commands.execute(forwarded, LINE)
                    .toCompletableFuture()
                    .handle((ran, failure) -> null)
                    .join();
        } finally {
            RoutingHolder.uninstall(a);
        }
        assertTrue(forwarded.left());
        assertTrue(heard.isEmpty());
        assertTrue(there.lines.isEmpty());
    }

    /** A platform with no players of its own. */
    private static final class Offline implements CloudPlatformCommandArguments {
        @Override
        public ParserDescriptor<Sender, PlayerSelector> singlePlayerSelectorParser() {
            return ParserDescriptor.of(
                    StringParser.<Sender>stringParser()
                            .parser()
                            .flatMapSuccess((context, name) -> ArgumentParseResult.<PlayerSelector>failureFuture(
                                    new IllegalArgumentException("Player is not online: " + name))),
                    PlayerSelector.class);
        }

        @Override
        public SuggestionProvider<Sender> onlinePlayerSuggestions() {
            return SuggestionProvider.noSuggestions();
        }
    }

    @Test
    void withoutATransportAPlayerElsewhereGetsTheNetworkDisabledMessage() {
        NetworkService alone = service("p3", new Players(), null, false, new Recorder());
        alone.start();
        assertFalse(alone.run(staff, LINE, aliceOn("p7")).join());
        assertTrue(staff.plain().getFirst().contains("Network-wide commands are not enabled"), staff.plain()::toString);
    }

    @Test
    void theSenderGrantsTheCommandNodesItHoldsAndTheConsoleAllOfThem() {
        NetworkService a = started("p3", new Players(), new Recorder());
        NetworkService b = started("p7", new Players().with(ALICE, "Alice"), new Recorder());
        List<JsonObject> requests = requests(b);

        a.run(staff, LINE, aliceOn("p7")).join();
        a.run(new Staff(true, Set.of()), LINE, aliceOn("p7")).join();

        JsonObject player = requests.get(0);
        assertEquals(List.of("cult.cloud.view", "cult.debug"), nodes(player.getAsJsonArray("granted")));
        assertEquals(MOD.toString(), player.getAsJsonObject("sender").get("u").getAsString());
        assertEquals("Mod", player.getAsJsonObject("sender").get("n").getAsString());
        assertFalse(player.getAsJsonObject("sender").get("console").getAsBoolean());
        assertEquals(ALICE.toString(), player.get("target").getAsString());
        assertEquals(LINE, player.get("line").getAsString());

        JsonObject console = requests.get(1);
        assertEquals(
                List.of("cult.cloud.view", "cult.consoledebug", "cult.debug", "cult.profile"),
                nodes(console.getAsJsonArray("granted")));
        assertTrue(console.getAsJsonObject("sender").get("console").getAsBoolean());
    }

    private static List<String> nodes(JsonArray granted) {
        List<String> nodes = new ArrayList<>();
        for (JsonElement node : granted) nodes.add(node.getAsString());
        return nodes;
    }

    @Test
    void aRemoteSourceHoldsOnlyGrantedCommandNodesOutsideTheDenylist() {
        RemoteCommandSource source = new RemoteCommandSource(
                MOD,
                "Mod",
                false,
                List.of(
                        "cult.debug",
                        "cult.cloud",
                        "cult.cloud.view",
                        "cult.cloud.label",
                        "cult.update",
                        "cult.runtimes",
                        "cult.secret"),
                Set.of(
                        "cult.debug",
                        "cult.cloud",
                        "cult.cloud.view",
                        "cult.cloud.label",
                        "cult.update",
                        "cult.consoledebug"),
                component -> {},
                clock::get,
                null);
        assertTrue(source.hasPermission("cult.debug"));
        assertTrue(source.hasPermission("cult.debug", false));
        assertTrue(source.hasPermission("cult.cloud.view"));
        for (String refused : List.of(
                "cult.cloud", "cult.cloud.label", "cult.update", "cult.runtimes", "cult.secret", "cult.consoledebug"))
            assertFalse(source.hasPermission(refused) || source.hasPermission(refused, true), refused);
        assertFalse(source.isConsole());
        assertFalse(source.isPlayer());
        assertNull(source.getPlatformPlayer());
        assertSame(source, source.getNativeSender());

        RemoteCommandSource console = new RemoteCommandSource(
                Sender.CONSOLE_UUID,
                Sender.CONSOLE_NAME,
                true,
                List.of("cult.debug", "cult.devswap"),
                Set.of("cult.debug", "cult.devswap", "cult.consoledebug"),
                component -> {},
                clock::get,
                "platform console");
        assertTrue(console.isConsole());
        assertTrue(console.hasPermission("cult.debug"));
        assertFalse(console.hasPermission("cult.devswap"));
        assertFalse(console.hasPermission("cult.consoledebug"));
        assertEquals("platform console", console.getNativeSender());
    }

    @Test
    void aRemoteSourceRelaysOutputUntilThirtySecondsAfterItsCommandCompleted() {
        List<Component> relayed = new ArrayList<>();
        RemoteCommandSource source =
                new RemoteCommandSource(MOD, "Mod", false, List.of(), NODES, relayed::add, clock::get, null);
        source.performCommand("cult reload");
        assertTrue(relayed.isEmpty());
        source.sendMessage(Component.text("a"));
        source.sendMessage("§cb");
        assertEquals(
                NamedTextColor.RED,
                relayed.get(1).children().isEmpty()
                        ? relayed.get(1).color()
                        : relayed.get(1).children().getFirst().color());

        source.done();
        clock.addAndGet(RemoteCommandSource.LINGER_MILLIS);
        source.sendMessage(Component.text("c"));
        clock.addAndGet(1);
        source.sendMessage(Component.text("d"));
        assertFalse(source.isValid());
        assertEquals(
                List.of("a", "b", "c"),
                relayed.stream()
                        .map(PlainTextComponentSerializer.plainText()::serialize)
                        .toList());
    }
}
