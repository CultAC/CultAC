package ac.cult.cultac.network.routing;

import static ac.cult.cultac.network.routing.NetSettings.CHECK;
import static ac.cult.cultac.network.routing.NetSettings.OBSERVE;
import static ac.cult.cultac.network.routing.NetSettings.OFF;
import static ac.cult.cultac.network.routing.NetSettings.ON;
import static ac.cult.cultac.network.routing.NetSettings.TOGGLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.network.routing.NetSettings.Baseline;
import ac.cult.cultac.network.routing.NetSettings.Record;
import ac.cult.cultac.network.routing.NetSettings.Setting;
import ac.cult.cultac.network.routing.NetSettings.Source;
import ac.cult.cultac.network.routing.NetSettings.Value;
import ac.cult.cultac.network.routing.TestNetworks.Players;
import ac.cult.cultac.platform.api.sender.Sender;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetSettingsTest {
    private static final String A = "00000000-0000-0000-0000-00000000000a";
    private static final String B = "00000000-0000-0000-0000-00000000000b";
    private static final String C = "00000000-0000-0000-0000-00000000000c";
    private static final String D = "00000000-0000-0000-0000-00000000000d";
    /** Speed's stable key. */
    private static final String SPEED = "grim.legacy.speed";
    /** The stable key of AttackEntityVisibility, formerly AttackMobVisibility. */
    private static final String ATTACK_ENTITY_VISIBILITY = "cult.attack.mob.visibility";

    private final AtomicLong clock = new AtomicLong(1_793_140_000_000L);
    private final FakeBroker broker = new FakeBroker();
    private final TestNetworks networks = new TestNetworks(broker, clock);

    @TempDir
    Path folder;

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

    /** A test instance keeping its shared settings in {@code directory}, null for memory only. */
    private static final class Net implements NetCommands.Local {
        private final @Nullable Path directory;
        volatile Baseline baseline = Baseline.DEFAULT;
        volatile NetCommands.Options options = NetCommands.Options.DEFAULT;

        Net(@Nullable Path directory) {
            this.directory = directory;
        }

        @Override
        public String build() {
            return "b1";
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
            return options;
        }

        @Override
        public Baseline baseline() {
            return baseline;
        }

        @Override
        public @Nullable Path stateFile() {
            return directory == null ? null : directory.resolve(NetSettings.FILE);
        }

        @Override
        public @Nullable Path configFile() {
            return directory == null ? null : directory.resolve("config.yml");
        }
    }

    private static Record record(String key, String value, String target, long at, String id) {
        return Record.of(key, value, target, at, "Mod", id);
    }

    private static List<Record> merged(List<Record> table, Collection<Record> incoming) {
        return NetSettings.merged(table, incoming, new HashSet<>());
    }

    private Path directory(String instance) throws IOException {
        return Files.createDirectories(folder.resolve(instance));
    }

    private JsonObject lastRoster() {
        List<JsonObject> rosters = networks.broadcasts(RosterCache.TYPE);
        return rosters.getLast();
    }

    @Test
    void recordsOrderByTimeThenIdWhateverOrderTheyArriveIn() {
        Record observe = record(OBSERVE, ON, "all", 10, B);
        Record lowId = record(TOGGLE, OFF, "p3", 20, A);
        Record highId = record(TOGGLE, ON, "P3", 20, B);
        Record later = record(TOGGLE, OFF, "lobby", 30, A);

        List<Record> forward = merged(List.of(), List.of(observe, lowId, highId, later));
        List<Record> backward = new ArrayList<>();
        for (Record record : List.of(later, highId, lowId, observe)) backward = merged(backward, List.of(record));

        assertEquals(List.of(observe, highId, later), forward);
        assertEquals(forward, backward);
        assertSame(forward, merged(forward, List.of(lowId, observe)));
    }

    @Test
    void aRecordNamesInstancesByRoleIdAndPatternAndPlayersByTheirBackend() {
        Net proxyNet = new Net(null);
        NetworkService proxy = networks.service("p3")
                .platform(new Players().proxy(null).backends("BRG_1", "lobby"))
                .net(proxyNet)
                .start();
        NetworkService server = networks.service("p7").net(new Net(null)).start();
        NetSettings atProxy = proxy.netSettings();
        NetSettings atServer = server.netSettings();

        atProxy.merge(List.of(record(TOGGLE, OFF, "all", 10, A)));
        atProxy.merge(List.of(record(TOGGLE, ON, "BRG_1", 20, B)));
        assertFalse(atProxy.value(TOGGLE).on());
        assertFalse(atProxy.value(TOGGLE, "lobby").on());
        assertTrue(atProxy.value(TOGGLE, "brg_1").on());
        atProxy.merge(List.of(record(TOGGLE, ON, "all", 30, C)));
        assertTrue(atProxy.value(TOGGLE, "lobby").on());
        assertEquals(1, atProxy.records().size());

        List<Record> scoped = List.of(
                record(OBSERVE, ON, "proxies", 40, A),
                record(TOGGLE, OFF, "p*", 40, B),
                record(CHECK + "speed", OFF, "id:p7", 40, C));
        atProxy.merge(scoped);
        atServer.merge(scoped);
        assertTrue(atProxy.value(OBSERVE).on());
        assertFalse(atServer.value(OBSERVE).on());
        assertFalse(atProxy.value(TOGGLE).on());
        assertFalse(atServer.value(TOGGLE).on());
        assertFalse(atServer.value(CHECK + "speed").on());
        assertTrue(atProxy.value(CHECK + "speed", "p7").on());

        proxyNet.options = new NetCommands.Options(true, Set.of("status"), NetCommands.Webhook.NONE);
        atProxy.configLoaded();
        assertEquals(new Value(false, Source.CONFIG, null, null), atProxy.value(OBSERVE));
        assertTrue(atProxy.records().containsAll(scoped));
    }

    @Test
    void theIssuerStampsPastTheNewestRecordItHolds() {
        NetSettings settings = networks.service("p3").net(new Net(null)).start().netSettings();
        Setting off = new Setting(TOGGLE, false);
        assertEquals(clock.get(), settings.stamp(off, "all", "Mod", A).at());

        settings.merge(List.of(record(TOGGLE, ON, "BRG_1", clock.get() + 60_000, B)));
        Record stamped = settings.stamp(off, "all", "Mod", C);
        assertEquals(clock.get() + 60_001, stamped.at());
        assertEquals(
                clock.get(),
                settings.stamp(new Setting(OBSERVE, true), "all", "Mod", D).at());

        settings.merge(List.of(stamped));
        assertFalse(settings.value(TOGGLE, "BRG_1").on());
        assertNull(record(TOGGLE, ON, "all", Long.MAX_VALUE, D));
    }

    @Test
    void anAllRecordDropsTheOlderRecordsOfItsKeyAndOutranksLateOnes() {
        Record observe = record(OBSERVE, ON, "p3", 5, A);
        List<Record> table = merged(
                List.of(), List.of(record(TOGGLE, OFF, "BRG_1", 10, A), record(TOGGLE, OFF, "id:p3", 11, B), observe));
        Record all = record(TOGGLE, ON, "all", 20, C);

        table = merged(table, List.of(all));
        assertEquals(List.of(observe, all), table);
        assertSame(table, merged(table, List.of(record(TOGGLE, OFF, "BRG_1", 15, D))));
        Record newer = record(TOGGLE, OFF, "BRG_1", 25, D);
        assertEquals(List.of(observe, all, newer), merged(table, List.of(newer)));

        List<Record> many = new ArrayList<>();
        for (int i = 0; i < 600; i++) many.add(record(CHECK + "c" + i, OFF, "all", i + 1, A));
        List<Record> capped = merged(List.of(), many);
        assertEquals(NetSettings.MAX_RECORDS, capped.size());
        assertEquals(600 - NetSettings.MAX_RECORDS + 1, capped.getFirst().at());

        Record deciding = record(TOGGLE, OFF, "all", 1, A);
        List<Record> crowded = new ArrayList<>(List.of(deciding));
        for (int i = 0; i < 515; i++) crowded.add(record(CHECK + "c" + i, OFF, "BRG_" + i, i + 2, B));
        for (int i = 0; i < 20; i++) crowded.add(record(OBSERVE, ON, "lobby" + i, 600 + i, C));
        Record lobbies = record(OBSERVE, OFF, "lobby*", 700, D);
        crowded.add(lobbies);
        capped = merged(List.of(), crowded);
        assertEquals(NetSettings.MAX_RECORDS, capped.size());
        assertTrue(capped.contains(deciding) && capped.contains(lobbies) && capped.contains(crowded.get(6)));
        assertFalse(capped.contains(crowded.get(5)) || capped.contains(crowded.get(535)));
    }

    @Test
    void anInstanceBootsFromItsFileAndCatchesUpFromRostersAndSync() throws IOException {
        Path p7Folder = directory("p7");
        NetworkService p3 = networks.service("p3").net(new Net(directory("p3"))).start();
        NetworkService p7 = networks.service("p7").net(new Net(p7Folder)).start();

        Record first = record(OBSERVE, ON, "all", clock.get(), A);
        p3.netSettings().merge(List.of(first));
        networks.tick(p3);
        assertEquals(List.of(first), NetSettings.records(lastRoster()));
        assertEquals(List.of(first), p7.netSettings().records());

        networks.stop(p7);
        Record second = record(TOGGLE, OFF, "all", clock.get() + 1, B);
        p3.netSettings().merge(List.of(second));
        TestNetworks.sendDelta(p3);

        Path restarted = directory("p7-restarted");
        Files.copy(p7Folder.resolve(NetSettings.FILE), restarted.resolve(NetSettings.FILE));
        NetworkService offline =
                networks.service("p7").net(new Net(restarted)).transport(null).start();
        assertEquals(List.of(first), offline.netSettings().records());
        networks.stop(offline);

        networks.advance(NetSettings.SYNC_INTERVAL_MILLIS);
        NetworkService back = networks.service("p7").net(new Net(restarted)).start();
        assertEquals(List.of(first, second), back.netSettings().records());
        assertFalse(back.netSettings().value(TOGGLE).on());

        Record third = record(CHECK + "speed", OFF, "BRG_*", clock.get() + 2, C);
        p3.netSettings().merge(List.of(third));
        TestNetworks.sendDelta(p3);
        assertEquals(List.of(third), NetSettings.records(lastRoster()));
        assertEquals(p3.netSettings().records(), back.netSettings().records());
    }

    @Test
    void anEnvironmentPinIsReportedAndRecordsNothing() {
        assertEquals(
                List.of(SPEED, ATTACK_ENTITY_VISIBILITY),
                BaseConfigManager.checksOffPin(
                        Map.of(BaseConfigManager.CHECKS_OFF_ENV, " Speed, ,attackmobvisibility,NoSuchCheck")::get));
        Net net = new Net(null);
        net.baseline = new Baseline(false, false, false, List.of(), null, false, null, List.of(SPEED));
        NetSettings settings = networks.service("p3").net(net).start().netSettings();
        settings.configLoaded();
        assertEquals(List.of(), settings.records());

        Record observe = record(OBSERVE, ON, "all", 10, A);
        Record speed = record(CHECK + SPEED, ON, "all", 10, B);
        settings.merge(List.of(observe, speed));

        assertEquals(new Value(false, Source.PIN, observe, "CULTAC_OBSERVE_ONLY"), settings.value(OBSERVE));
        assertEquals(new Value(false, Source.PIN, speed, "CULTAC_CHECKS_OFF"), settings.value(CHECK + SPEED));
        assertEquals(List.of(observe, speed), settings.records());
    }

    @Test
    void aHandEditBecomesARecordForThisInstanceAtTheFilesTimeUntilANewerCommand() throws IOException {
        Path directory = directory("p3");
        Path config = directory.resolve("config.yml");
        Files.writeString(
                config,
                "# shared\ndormant: false\nobserve-only: false\nchecks-off: []\npause-punishments: false\n"
                        + "max-clicks-per-tick: 3\n");
        NetSettings settings =
                networks.service("p3").net(new Net(directory)).start().netSettings();
        settings.configLoaded();
        assertEquals(List.of(), settings.records());

        Files.writeString(
                config,
                Files.readString(config)
                        .replace("observe-only: false", "observe-only: true")
                        .replace("checks-off: []", "checks-off: [attackmobvisibility]"));
        long edited = clock.get() - 5_000;
        Files.setLastModifiedTime(config, FileTime.fromMillis(edited));
        settings.configLoaded();

        List<Record> edits = settings.records();
        assertEquals(2, edits.size());
        for (Record edit : edits) {
            assertEquals("id:p3", edit.t());
            assertEquals(NetSettings.HAND_EDIT, edit.by());
            assertEquals(edited, edit.at());
        }
        assertTrue(settings.value(OBSERVE).on());
        assertFalse(settings.value(CHECK + ATTACK_ENTITY_VISIBILITY).on());
        settings.configLoaded();
        assertEquals(edits, settings.records());

        settings.merge(List.of(record(OBSERVE, OFF, "all", clock.get(), A)));
        assertFalse(settings.value(OBSERVE).on());
        String written = Files.readString(config);
        assertTrue(written.contains("observe-only: false"), written);
        assertTrue(written.contains("checks-off: [attackmobvisibility]"), written);
        assertTrue(written.startsWith("# shared") && written.contains("max-clicks-per-tick: 3"), written);
        assertEquals(
                false,
                NetSettings.written(NetStateFile.read(directory.resolve(NetSettings.FILE), null)
                                .get("written"))
                        .get("observe-only"));

        settings.configLoaded();
        assertEquals(2, settings.records().size());
        assertFalse(settings.value(OBSERVE).on());

        settings.merge(List.of(record(CHECK + ATTACK_ENTITY_VISIBILITY, ON, "all", clock.get() + 1, B)));
        assertTrue(settings.value(CHECK + ATTACK_ENTITY_VISIBILITY).on());
        assertTrue(Files.readString(config).contains("checks-off: []"), Files.readString(config));
        settings.merge(List.of(record(CHECK + SPEED, OFF, "all", clock.get() + 2, C)));
        assertTrue(Files.readString(config).contains("checks-off: [Speed]"), Files.readString(config));
        settings.configLoaded();
        assertEquals(3, settings.records().size());
    }

    @Test
    void aSettingForANameNoProxyListsIsKeptAndSpreadsAndACommandsRecordReachesItsTargets() {
        NetworkService proxy = networks.service("p3")
                .platform(new Players().proxy(null).backends("BRG_1"))
                .net(new Net(null))
                .start();
        NetworkService server = networks.service("p7").net(new Net(null)).start();
        networks.tick(proxy);
        NetCommands.Spec toggle = new NetCommands.Spec(
                "toggle",
                1_000,
                true,
                args -> args.size() == 1,
                args -> new Setting(TOGGLE, args.getFirst().equals(ON)));
        List<String> heard = new CopyOnWriteArrayList<>();
        Sender staff = mock(Sender.class);
        when(staff.getName()).thenReturn("Mod");
        when(staff.getUniqueId()).thenReturn(UUID.fromString(D));
        when(staff.hasPermission(anyString())).thenReturn(true);
        doAnswer(invocation -> heard.add(
                        PlainTextComponentSerializer.plainText().serialize(invocation.<Component>getArgument(0))))
                .when(staff)
                .sendMessage(any(Component.class));

        server.netCommands().issue(staff, "BRG_9", toggle, List.of(OFF)).join();
        assertTrue(
                heard.stream()
                        .anyMatch(line -> line.endsWith("No proxy lists BRG_9; the setting applies when it appears.")),
                heard.toString());
        Record unlisted = server.netSettings().records().getFirst();
        assertEquals("BRG_9", unlisted.t());
        assertEquals("Mod", unlisted.by());

        TestNetworks.sendDelta(server);
        assertEquals(server.netSettings().records(), proxy.netSettings().records());
        assertFalse(proxy.netSettings().value(TOGGLE, "BRG_9").on());
        assertTrue(proxy.netSettings().value(TOGGLE, "BRG_1").on());

        CompletableFuture<Void> done = server.netCommands().issue(staff, "BRG_1", toggle, List.of(OFF));
        assertFalse(proxy.netSettings().value(TOGGLE, "BRG_1").on());
        server.netCommands().expireAll().join();
        done.join();
    }
}
