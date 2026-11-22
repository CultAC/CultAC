package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.command.commands.CultNet;
import ac.cult.cultac.network.routing.TestNetworks.Players;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.incendo.cloud.suggestion.Suggestion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NetCommandsTest {
    private static final UUID MOD = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final AtomicLong clock = new AtomicLong(1_793_140_000_000L);
    private final FakeBroker broker = new FakeBroker();
    private final TestNetworks networks = new TestNetworks(broker, clock);
    private final Staff staff = new Staff(Set.of("cult.net", "cult.net.status"));

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
        NetworkModule.stop();
        placeholders.close();
    }

    /** How a test instance describes itself; a test may change its options between commands. */
    private static final class Net implements NetCommands.Local {
        volatile NetCommands.Options options = NetCommands.Options.DEFAULT;
        volatile int dormant;

        @Override
        public String build() {
            return "b1";
        }

        @Override
        public RosterCache.State state(NetworkPlatform platform) {
            int players = platform.onlinePlayers().size();
            return new RosterCache.State(true, false, List.of(), List.of(), players, players, dormant);
        }

        @Override
        public int dormantPlayers(@Nullable List<String> backends) {
            return dormant;
        }

        @Override
        public Map<String, String> details() {
            return Map.of("generations", "1", "prediction", "0.100", "uptime", "5m", "engine_uptime", "4m");
        }

        @Override
        public NetCommands.Options options() {
            return options;
        }
    }

    /** A staff member holding {@code permissions}. */
    private static final class Staff implements Sender {
        final List<Component> heard = new CopyOnWriteArrayList<>();
        final Set<String> permissions;

        Staff(Set<String> permissions) {
            this.permissions = permissions;
        }

        List<String> plain() {
            return heard.stream()
                    .map(PlainTextComponentSerializer.plainText()::serialize)
                    .toList();
        }

        @Override
        public String getName() {
            return "Mod";
        }

        @Override
        public UUID getUniqueId() {
            return MOD;
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
            throw new AssertionError("network commands are not command lines");
        }

        @Override
        public boolean isConsole() {
            return false;
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

    private NetworkService started(String instance, Players players, Net net) {
        return networks.service(instance).platform(players).net(net).start();
    }

    /** The bodies of the {@code type} messages {@code service} receives. */
    private static List<JsonObject> received(NetworkService service, String type) {
        List<JsonObject> bodies = new CopyOnWriteArrayList<>();
        service.on(type, envelope -> bodies.add(envelope.body()));
        return bodies;
    }

    /** Runs {@code status} on {@code target} from {@code service} and waits for its summary. */
    private void status(NetworkService service, String target) {
        CompletableFuture<Void> done = service.netCommands().issue(staff, target, "status", List.of());
        service.netCommands().expireAll().join();
        done.join();
    }

    /** Runs {@code command} on {@code target} from {@code service} for {@code sender} and waits for its summary. */
    private static void run(NetworkService service, Sender sender, String target, String command, String... args) {
        CompletableFuture<Void> done = service.netCommands().issue(sender, target, command, List.of(args));
        service.netCommands().expireAll().join();
        done.join();
    }

    /** {@code status} from Mod on p3 for {@code target}, granting {@code granted}. */
    private static JsonObject command(String target, String... granted) {
        JsonObject sender = new JsonObject();
        sender.addProperty("u", MOD.toString());
        sender.addProperty("n", "Mod");
        sender.addProperty("console", false);
        JsonArray nodes = new JsonArray();
        for (String node : granted) nodes.add(node);
        JsonObject body = new JsonObject();
        body.addProperty("id", UUID.randomUUID().toString());
        body.addProperty("cmd", "status");
        body.add("args", new JsonArray());
        body.addProperty("target", target);
        body.addProperty("origin", "p3");
        body.add("sender", sender);
        body.add("granted", nodes);
        return body;
    }

    /** A full roster from {@code instance} with no players; one without {@code role} is what an older build sends. */
    private void roster(String instance, @Nullable String role) {
        JsonObject body = RosterCache.body(List.of(), true, List.of(), Set.of(), "n");
        body.addProperty(RosterCache.ORDER, 1);
        if (role != null) body.addProperty(RosterCache.ROLE, role);
        EnvelopeCodec codec = new EnvelopeCodec(
                TestNetworks.KEY, instance, type -> true, new SharedClaims(null, "envelope", clock::get));
        broker.transport()
                .publish(
                        TestNetworks.channel(NetworkSettings.ALL),
                        codec.encode(
                                Envelope.create(RosterCache.TYPE, instance, NetworkSettings.ALL, clock.get(), body)));
    }

    private static List<String> codes(List<JsonObject> replies) {
        return replies.stream().map(reply -> reply.get("code").getAsString()).toList();
    }

    private boolean heard(String line) {
        return staff.plain().stream().anyMatch(text -> text.endsWith(line));
    }

    @Test
    void aSenderWithoutTheCommandsNodeSendsNothing() {
        NetworkService a = started("p3", new Players(), new Net());
        NetworkService b = started("p7", new Players(), new Net());
        List<JsonObject> atA = received(a, NetCommands.CMD);
        List<JsonObject> atB = received(b, NetCommands.CMD);
        Staff without = new Staff(Set.of("cult.net"));

        a.netCommands().issue(without, "all", "status", List.of()).join();

        assertTrue(atA.isEmpty());
        assertTrue(atB.isEmpty());
        assertEquals(List.of("refused: Mod lacks cult.net.status here"), without.plain());
    }

    @Test
    void aTargetRefusesWithoutTheGrantedNodeWhileDisabledAndForACommandItDoesNotAccept() {
        NetworkService a = started("p3", new Players(), new Net());
        Net there = new Net();
        started("p7", new Players(), there);
        List<JsonObject> replies = received(a, NetCommands.REPLY);

        a.send("p7", NetCommands.CMD, command("p7", "cult.net"));
        there.options = new NetCommands.Options(false, NetCommands.Options.DEFAULT.accept(), NetCommands.Webhook.NONE);
        a.send("p7", NetCommands.CMD, command("p7", "cult.net", "cult.net.status"));
        there.options = new NetCommands.Options(true, Set.of("reload"), NetCommands.Webhook.NONE);
        a.send("p7", NetCommands.CMD, command("p7", "cult.net", "cult.net.status"));

        assertEquals(List.of("refused-permission", "refused-disabled", "refused-not-accepted"), codes(replies));
    }

    @Test
    void onlyTheCurrentGenerationRunsACommandAndOnlyOnce() {
        Map<String, Object> shared = new ConcurrentHashMap<>();
        NetworkService a = started("p3", new Players(), new Net());
        List<JsonObject> replies = received(a, NetCommands.REPLY);
        networks.service("p7")
                .host(TestNetworks.host(shared, false))
                .net(new Net())
                .start();
        JsonObject body = command("p7", "cult.net", "cult.net.status");

        a.send("p7", NetCommands.CMD, body);
        assertTrue(replies.isEmpty());

        networks.service("p7")
                .host(TestNetworks.host(shared, true))
                .net(new Net())
                .start();
        a.send("p7", NetCommands.CMD, body);
        a.send("p7", NetCommands.CMD, body);
        assertEquals(List.of("status"), codes(replies));
    }

    @Test
    void theIssuerShowsEachAnswerThenWhoDidNotAnswerAndWhoRunsAnOlderBuild() {
        NetworkService a = started("p3", new Players().proxy(null), new Net());
        started("p7", new Players().with(ALICE, "Alice", null), new Net());
        roster("p5", null);
        roster("p9", RosterCache.SERVER);

        status(a, "all");

        assertTrue(heard("status sent to 3 instances (all)"), staff.plain().toString());
        assertTrue(
                staff.plain()
                        .contains(
                                "p3 » b1 · proxy · 0 players, 0 inspected, 0 dormant · checking · enforcing · punishing ·"
                                        + " checks off: none · 0.100 ms/prediction · up 5m (engine 4m, 1 generations)"),
                staff.plain().toString());
        assertTrue(staff.plain().stream().anyMatch(line -> line.startsWith("p7 » b1 · server · 1 players")));
        assertTrue(heard("2 of 3 answered"), staff.plain().toString());
        assertTrue(heard("Did not answer: p9"), staff.plain().toString());
        assertTrue(
                heard("Older builds (cannot run network commands): p5"),
                staff.plain().toString());
    }

    @Test
    void aBackendTermReachesTheProxiesListingItForThePlayersOnIt() {
        NetworkService proxy = started(
                "p3",
                new Players()
                        .proxy(null)
                        .backends("lobby", "BRG_2", "BRG_1")
                        .with(ALICE, "Alice", "BRG_1")
                        .with(BOB, "Bob", "lobby"),
                new Net());
        NetworkService server = started("p7", new Players(), new Net());
        networks.tick(proxy);
        List<JsonObject> atProxy = received(proxy, NetCommands.CMD);

        status(server, "brg_*");

        assertEquals(1, atProxy.size());
        assertEquals(
                List.of("brg_*"),
                atProxy.getFirst().getAsJsonArray("scope").asList().stream()
                        .map(JsonElement::getAsString)
                        .toList());
        assertTrue(
                staff.plain()
                        .contains("p3 » b1 · proxy · on BRG_1, BRG_2: 1 players, 1 inspected, 0 dormant · checking ·"
                                + " enforcing · punishing · checks off: none"),
                staff.plain().toString());
        assertTrue(heard("1 of 1 answered"), staff.plain().toString());

        staff.heard.clear();
        server.netCommands().issue(staff, "BRG_9", "status", List.of()).join();
        assertEquals(1, atProxy.size());
        assertTrue(
                heard("No proxy or server is named BRG_9; nothing was sent."),
                staff.plain().toString());
    }

    @Test
    void punishAndCheckSwitchThePlayersOnTheBackendsNamedAndStatusShowsEachBackend() {
        NetworkService proxy = started(
                "p3", new Players().proxy(null).backends("BRG_1", "BRG_2").with(ALICE, "Alice", "BRG_1"), new Net());
        NetworkService server = started("p7", new Players(), new Net());
        networks.tick(proxy);
        Staff mod = new Staff(Set.of("cult.net", "cult.net.status", "cult.net.punish", "cult.net.check"));

        run(server, mod, "BRG_1", "punish", "off");
        run(server, mod, "BRG_1", "check", "speed", "off");
        run(server, mod, "p3", "check", "NoSuchCheck", "off");
        run(server, mod, "brg_*", "status");

        List<String> heard = mod.plain();
        assertTrue(heard.contains("p3 » players on BRG_1: now punishments paused"), heard.toString());
        assertTrue(heard.contains("p3 » Speed off (players on BRG_1)"), heard.toString());
        assertTrue(heard.contains("p7 » no check NoSuchCheck here"), heard.toString());
        assertTrue(
                heard.contains("p3 » b1 · proxy · on BRG_1, BRG_2: 1 players, 1 inspected, 0 dormant · checking ·"
                        + " enforcing · punishments paused on BRG_1; punishing on BRG_2 · checks off: Speed on BRG_1; none on"
                        + " BRG_2"),
                heard.toString());
    }

    @Test
    void toggleAndStatusCountTheDormantPlayersAndStatusShowsTheToggleOfEachBackend() {
        Net net = new Net();
        net.dormant = 1;
        NetworkService proxy = started(
                "p3", new Players().proxy(null).backends("BRG_1", "BRG_2").with(ALICE, "Alice", "BRG_1"), net);
        NetworkService server = started("p7", new Players(), new Net());
        networks.tick(proxy);
        Staff mod = new Staff(Set.of("cult.net", "cult.net.status", "cult.net.toggle"));

        run(server, mod, "BRG_1", "toggle", "off");
        run(server, mod, "brg_*", "status");

        List<String> heard = mod.plain();
        assertTrue(heard.contains("p3 » players on BRG_1: now dormant (1 players not checked)"), heard.toString());
        assertTrue(
                heard.contains("p3 » b1 · proxy · on BRG_1, BRG_2: 1 players, 1 inspected, 1 dormant · dormant on"
                        + " BRG_1; checking on BRG_2 · enforcing · punishing · checks off: none"),
                heard.toString());
    }

    @Test
    void theTargetCompletesNamesBackendsAndGroupsAfterEachCommaAndThenTheCommandsForIt() {
        NetworkService proxy = started("p3", new Players().proxy(null).backends("BRG_1", "BRG_2", "lobby"), new Net());
        NetworkService server = started("p7", new Players(), new Net());
        networks.tick(proxy);
        assertEquals(
                List.of("all", "proxies", "p3", "p7", "BRG_1", "BRG_2", "lobby", "BRG_*", "p*"),
                server.netCommands().targets(""));
        assertEquals(List.of("p3", "p3,"), server.netCommands().targets("p3"));
        assertEquals(
                List.of("BRG_*,all", "BRG_*,proxies", "BRG_*,p3", "BRG_*,p7", "BRG_*,lobby", "BRG_*,p*"),
                server.netCommands().targets("BRG_*,"));

        TestNetworks.install(server);
        CommandManager<Sender> manager =
                new CommandManager<>(
                        ExecutionCoordinator.simpleCoordinator(),
                        CommandRegistrationHandler.nullCommandRegistrationHandler()) {
                    @Override
                    public boolean hasPermission(Sender sender, String permission) {
                        return true;
                    }
                };
        new CultNet().register(manager, null);
        assertEquals(List.of("brg_1,BRG_2", "brg_1,BRG_*"), suggest(manager, "cult net brg_1,b"));
        assertEquals(List.of("check", "observe", "punish", "status", "toggle"), suggest(manager, "cult net BRG_1,p7 "));
    }

    @Test
    void aForwardedCommandLineCanNeverStartANetworkCommand() {
        NetworkService a = started("p3", new Players(), new Net());
        NetworkService b = started("p7", new Players(), new Net());
        List<JsonObject> atA = received(a, NetCommands.CMD);
        List<JsonObject> atB = received(b, NetCommands.CMD);
        List<Component> heard = new CopyOnWriteArrayList<>();
        RemoteCommandSource console =
                new RemoteCommandSource(MOD, "Console", true, Set.of(), Set.of(), heard::add, null);

        a.netCommands().issue(console, "all", "status", List.of()).join();

        assertTrue(atA.isEmpty());
        assertTrue(atB.isEmpty());
        assertEquals(1, heard.size());
    }

    @Test
    void anInstanceHeardFromBeforeItsRosterIsAskedAndAwaited() {
        started("p7", new Players(), new Net());
        NetworkService a = started("p3", new Players(), new Net());

        status(a, "p7");

        assertTrue(
                staff.plain().stream().anyMatch(line -> line.startsWith("p7 » b1")),
                staff.plain().toString());
        assertTrue(heard("1 of 1 answered"), staff.plain().toString());
    }

    private List<String> suggest(CommandManager<Sender> manager, String input) {
        return manager.suggestionFactory().suggestImmediately(staff, input).list().stream()
                .map(Suggestion::suggestion)
                .toList();
    }
}
