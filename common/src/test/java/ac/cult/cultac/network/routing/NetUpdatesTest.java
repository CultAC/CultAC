package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.routing.TestNetworks.Players;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NetUpdatesTest {
    private static final String RUNNING = "0.1.0-b149-dev-abc1234@0123456789ab";
    private static final LongFunction<String> ACTIVATES =
            build -> "b149 → b" + build + " (dev abc1234), 0 players draining";

    private final AtomicLong clock = new AtomicLong(1_793_140_000_000L);
    private final TestNetworks networks = new TestNetworks(new FakeBroker(), clock);
    private final Console console = new Console();
    private final List<String> installs = new CopyOnWriteArrayList<>();
    private final AtomicInteger lookups = new AtomicInteger();

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

    /** A loader's host API from before it installed builds. */
    interface OlderHost {
        String build();
    }

    /** A proxy on {@value #RUNNING} whose loader records each install and answers it with {@code line}. */
    private final class Proxy implements NetCommands.Local, NetUpdates.Builds {
        private final String id;
        private final LongFunction<String> line;
        private final NetUpdates.@Nullable Builds builds;

        Proxy(String id, LongFunction<String> line, NetUpdates.@Nullable Builds builds) {
            this.id = id;
            this.line = line;
            this.builds = builds;
        }

        @Override
        public String build() {
            return RUNNING;
        }

        @Override
        public RosterCache.State state(NetworkPlatform platform) {
            return new RosterCache.State(true, false, List.of(), List.of(), 0, 0, 0);
        }

        @Override
        public Map<String, String> details() {
            return Map.of();
        }

        @Override
        public NetCommands.Options options() {
            return NetCommands.Options.DEFAULT;
        }

        @Override
        public NetUpdates.Builds builds() {
            return builds == null ? this : builds;
        }

        @Override
        public boolean installs() {
            return true;
        }

        @Override
        public String loader() {
            return "0.1.0-b142-dev-1234567";
        }

        @Override
        public CompletionStage<String> install(long number, @Nullable String branch, String requestedBy) {
            installs.add(id + " b" + number + " " + branch + " " + requestedBy);
            return CompletableFuture.completedFuture(line.apply(number));
        }

        @Override
        public CompletionStage<Long> latest(String channel) {
            lookups.incrementAndGet();
            return CompletableFuture.completedFuture(150L);
        }
    }

    private static final class Console implements Sender {
        final List<Component> heard = new CopyOnWriteArrayList<>();

        List<String> plain() {
            return heard.stream()
                    .map(PlainTextComponentSerializer.plainText()::serialize)
                    .toList();
        }

        @Override
        public String getName() {
            return Sender.CONSOLE_NAME;
        }

        @Override
        public UUID getUniqueId() {
            return Sender.CONSOLE_UUID;
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
            return true;
        }

        @Override
        public boolean hasPermission(String permission, boolean defaultIfUnset) {
            return true;
        }

        @Override
        public void performCommand(String commandLine) {
            throw new AssertionError("network commands are not command lines");
        }

        @Override
        public boolean isConsole() {
            return true;
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

    private NetworkService proxy(String id, LongFunction<String> line) {
        return proxy(id, line, null);
    }

    private NetworkService proxy(String id, LongFunction<String> line, NetUpdates.@Nullable Builds builds) {
        return networks.service(id)
                .platform(new Players().proxy(null))
                .net(new Proxy(id, line, builds))
                .start();
    }

    private void update(NetworkService issuer, String target, String... args) throws Exception {
        issuer.netCommands().issue(console, target, "update", List.of(args)).get(10, TimeUnit.SECONDS);
    }

    private boolean heard(String line) {
        return console.plain().stream().anyMatch(text -> text.endsWith(line));
    }

    @Test
    void theIssuerInstallsTheBuildTheCanaryActivatedLastAfterTheOthersAnswered() throws Exception {
        NetworkService p3 = proxy("p3", ACTIVATES);
        proxy("p5", ACTIVATES);
        proxy("p7", ACTIVATES);

        update(p3, "proxies", "latest", "--canary");

        assertEquals(
                List.of("p5 b150 dev Console from p3", "p7 b150 dev Console from p3", "p3 b150 dev Console from p3"),
                installs);
        assertEquals(1, lookups.get());
        List<String> lines = console.plain();
        assertTrue(heard("p3 updates itself last"), lines.toString());
        assertTrue(heard("canary p5 first..."), lines.toString());
        int othersDone = lines.indexOf("p7 » b149 → b150 (dev abc1234), 0 players draining");
        int issuerStarts = lines.indexOf("p3 » updating to b150: downloading and verifying...");
        assertTrue(othersDone >= 0 && issuerStarts > othersDone, lines.toString());
        assertTrue(lines.subList(othersDone, issuerStarts).stream().anyMatch(line -> line.endsWith("1 of 1 answered")));
        assertEquals("p3 » b149 → b150 (dev abc1234), 0 players draining", lines.get(lines.size() - 1));
    }

    @Test
    void aCanaryThatDoesNotActivateTheBuildStopsTheRestAndTheIssuer() throws Exception {
        NetworkService p3 = proxy("p3", ACTIVATES);
        proxy("p5", build -> "update refused: the manifest signature does not verify");
        proxy("p7", ACTIVATES);

        update(p3, "proxies", "150", "--canary");

        assertEquals(List.of("p5 b150 null Console from p3"), installs);
        assertTrue(
                heard("canary p5 did not activate b150; the rest were not updated"),
                console.plain().toString());
    }

    @Test
    void aProxyWhoseLoaderPredatesInstallsAnswersThatItNeedsARestart() throws Exception {
        NetworkService p3 = proxy("p3", ACTIVATES);
        proxy("p5", ACTIVATES, new NetUpdates.HostBuilds(OlderHost.class, () -> null, () -> "0.1.0-b142-dev-1234567"));

        update(p3, "p5", "150");

        assertTrue(installs.isEmpty());
        assertTrue(
                console.plain().contains("p5 » needs a proxy restart (loader b142 → b149)"),
                console.plain().toString());
    }
}
