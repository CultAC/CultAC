package ac.cult.cultac.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.platform.api.PlatformLoader;
import ac.cult.cultac.platform.api.PlatformServer;
import ac.cult.cultac.platform.api.scheduler.GlobalRegionScheduler;
import ac.cult.cultac.platform.api.scheduler.PlatformScheduler;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.config.ConfigManager;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PunishmentThresholdsTest {
    private final ConfigManager config = mock(ConfigManager.class);
    private final CultPlayer player = mock(CultPlayer.class);
    private final PunishmentLedger ledger = new PunishmentLedger(2);
    private final Check check;
    private long now = 1_000_000L;
    // Global tasks held back until the test runs them; null runs them at once.
    private List<Runnable> deferred;
    private PlatformServer server;

    PunishmentThresholdsTest() {
        when(config.getBooleanElse(anyString(), anyBoolean())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getIntElse(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getStringElse(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getBooleanElse("test-mode", false)).thenReturn(true);
        when(config.getStringElse(eq("alerts-format"), anyString())).thenReturn("vl %vl%");
        check = new Check(
                player,
                CheckInfo.builder()
                        .name("ThresholdProbe")
                        .stableKey("cult.test.threshold_probe")
                        .build()) {};
        check.setEnabled(true);
        doReturn(List.of(check)).when(player).getChecks();
    }

    @BeforeAll
    static void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    void flagsAtOneInstantEachCountTowardTheGroup() {
        PunishmentManager punishments = punishments("3:0 [alert]");

        for (int i = 0; i < 3; i++) flag(punishments);

        assertEquals(List.of("vl 3"), alerts());
    }

    @Test
    void aOneTimeCommandNoServerCouldRunRunsOnceWhenOneCan() throws Exception {
        List<String> ran = new ArrayList<>();
        AtomicBoolean reachable = new AtomicBoolean();
        try (var ignored = platform(ran, reachable)) {
            PunishmentManager punishments = punishments("2:0 ban probe");
            for (int i = 0; i < 3; i++) flag(punishments);
            punishments.deliverWaiting();
            assertEquals(List.of(), ran);

            reachable.set(true);
            punishments.deliverWaiting();
            flag(punishments);
            punishments.deliverWaiting();

            assertEquals(List.of("ban probe"), ran);
        }
    }

    @Test
    void aWaitingOneTimeCommandIsDroppedOnceItsFlagsLeaveTheWindow() throws Exception {
        List<String> ran = new ArrayList<>();
        AtomicBoolean reachable = new AtomicBoolean();
        try (var ignored = platform(ran, reachable)) {
            PunishmentManager punishments = punishments("2:0 ban probe");
            flag(punishments);
            flag(punishments);

            now += 301_000;
            reachable.set(true);
            punishments.deliverWaiting();

            assertEquals(List.of(), ran);
        }
    }

    @Test
    void aRunThatSettlesAfterThePlayerLeftDoesNotRunOnALaterConnection() throws Exception {
        List<String> ran = new ArrayList<>();
        AtomicBoolean reachable = new AtomicBoolean();
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        try (var ignored = platform(ran, reachable)) {
            deferred = new ArrayList<>();
            PunishmentManager first = punishments("2:0 ban probe");
            flag(first);
            flag(first);
            first.release();
            deferred.forEach(Runnable::run);
            deferred = null;

            now += 3_600_000;
            reachable.set(true);
            PunishmentManager second = punishments("2:0 ban probe");
            second.deliverWaiting();

            assertEquals(List.of(), ran);
        }
    }

    @Test
    void aOneTimeCommandWhoseRunFailedBeforeDispatchRunsOnTheNextFlag() throws Exception {
        List<String> ran = new ArrayList<>();
        try (var ignored = platform(ran, new AtomicBoolean(true))) {
            doThrow(new IllegalStateException("probe"))
                    .doReturn(false)
                    .when(server)
                    .dispatchesVerbose(anyString(), any());
            PunishmentManager punishments = punishments("2:0 ban probe");
            flag(punishments);
            flag(punishments);
            assertEquals(List.of(), ran);

            flag(punishments);

            assertEquals(List.of("ban probe"), ran);
        }
    }

    @Test
    void aOneTimeCommandWithheldAtItsThresholdRunsForTheFlagsAfter() throws Exception {
        List<String> ran = new ArrayList<>();
        try (var ignored = platform(ran, new AtomicBoolean(true))) {
            PunishmentManager punishments = punishments("2:0 ban probe");
            when(player.isObserveOnly()).thenReturn(true);
            flag(punishments);
            flag(punishments);
            when(player.isObserveOnly()).thenReturn(false);
            flag(punishments);
            assertEquals(List.of(), ran);

            flag(punishments);

            assertEquals(List.of("ban probe"), ran);
        }
    }

    @Test
    void flagsAndUsedCommandsCarryToTheNextConnectionWhileTheWindowLasts() throws Exception {
        List<String> ran = new ArrayList<>();
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        try (var ignored = platform(ran, new AtomicBoolean(true))) {
            PunishmentManager first = punishments("3:0 ban probe");
            flag(first);
            flag(first);
            first.release();

            PunishmentManager second = punishments("3:0 ban probe");
            flag(second);
            assertEquals(List.of("ban probe"), ran);
            second.release();

            now += 60_000;
            PunishmentManager third = punishments("3:0 ban probe");
            for (int i = 0; i < 3; i++) flag(third);

            assertEquals(List.of("ban probe"), ran);
        }
    }

    @Test
    void aConnectionAfterTheWindowCountsFromNothing() throws Exception {
        List<String> ran = new ArrayList<>();
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        try (var ignored = platform(ran, new AtomicBoolean(true))) {
            PunishmentManager first = punishments("3:0 ban probe");
            for (int i = 0; i < 3; i++) flag(first);
            first.release();

            now += 301_000;
            PunishmentManager second = punishments("3:0 ban probe");
            flag(second);
            flag(second);
            assertEquals(List.of("ban probe"), ran);
            flag(second);

            assertEquals(List.of("ban probe", "ban probe"), ran);
        }
    }

    @Test
    void aCommandAvailableAgainAfterItsWindowCountsOnlyLaterFlags() throws Exception {
        List<String> ran = new ArrayList<>();
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        try (var ignored = platform(ran, new AtomicBoolean(true))) {
            PunishmentManager first = punishments("3:0 ban probe");
            for (int i = 0; i < 3; i++) flag(first);
            now += 100_000;
            flag(first);
            flag(first);
            first.release();

            now += 201_000;
            PunishmentManager second = punishments("3:0 ban probe");
            flag(second);
            flag(second);
            assertEquals(List.of("ban probe"), ran);
            flag(second);

            assertEquals(List.of("ban probe", "ban probe"), ran);
        }
    }

    @Test
    void overlappingConnectionsCountInOneWindowAndRunACommandOnce() throws Exception {
        List<String> ran = new ArrayList<>();
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        try (var ignored = platform(ran, new AtomicBoolean(true))) {
            PunishmentManager first = punishments("2:0 ban probe");
            PunishmentManager second = punishments("2:0 ban probe");

            flag(first);
            flag(second);
            flag(first);
            flag(second);

            assertEquals(List.of("ban probe"), ran);
        }
    }

    @Test
    void aReloadStartsTheCountOverForConnectedAndDepartedPlayers() throws Exception {
        List<String> ran = new ArrayList<>();
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        try (var ignored = platform(ran, new AtomicBoolean(true))) {
            PunishmentManager first = punishments("3:0 ban probe");
            flag(first);
            flag(first);
            first.reload(config);
            flag(first);
            flag(first);
            first.release();

            ledger.clear();
            PunishmentManager second = punishments("3:0 ban probe");
            flag(second);
            flag(second);

            assertEquals(List.of(), ran);
        }
    }

    @Test
    void aConnectionThatEndsDuringAReloadLeavesNothingFromBeforeIt() throws Exception {
        List<String> ran = new ArrayList<>();
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        try (var ignored = platform(ran, new AtomicBoolean(true))) {
            PunishmentManager first = punishments("3:0 ban probe");
            flag(first);
            flag(first);
            ledger.clear();
            first.release();

            PunishmentManager second = punishments("3:0 ban probe");
            flag(second);

            assertEquals(List.of(), ran);
        }
    }

    @Test
    void overlappingConnectionsCountInOneWindowAfterAReload() throws Exception {
        List<String> ran = new ArrayList<>();
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        try (var ignored = platform(ran, new AtomicBoolean(true))) {
            PunishmentManager first = punishments("2:0 ban probe");
            PunishmentManager second = punishments("2:0 ban probe");
            ledger.clear();
            first.reload(config);
            second.reload(config);

            flag(first);
            flag(second);

            assertEquals(List.of("ban probe"), ran);
        }
    }

    @Test
    void departedPlayersAreKeptOnlyWithFlagsInTheWindowAndUpToTheCap() {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        punishments("3:0 [alert]").release();
        assertEquals(0, ledger.kept());

        for (int i = 0; i < 3; i++) {
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            PunishmentManager punishments = punishments("3:0 [alert]");
            flag(punishments);
            punishments.release();
        }
        assertEquals(2, ledger.kept());

        now += 301_000;
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        PunishmentManager punishments = punishments("3:0 [alert]");
        flag(punishments);
        punishments.release();

        assertEquals(1, ledger.kept());
    }

    /**
     * Runs global tasks at once and dispatches punishment commands into {@code ran} while {@code reachable} says a
     * server can run them.
     */
    private AutoCloseable platform(List<String> ran, AtomicBoolean reachable) throws ReflectiveOperationException {
        server = mock(PlatformServer.class);
        when(server.dispatchCommand(any(), anyString(), any(), any(), anyDouble(), anyString()))
                .thenAnswer(invocation -> {
                    if (reachable.get()) ran.add(invocation.getArgument(1));
                    return reachable.get();
                });
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        when(global.run(any(), any())).thenAnswer(invocation -> {
            if (deferred != null) {
                deferred.add(invocation.getArgument(1));
            } else {
                invocation.<Runnable>getArgument(1).run();
            }
            return null;
        });
        PlatformScheduler scheduler = mock(PlatformScheduler.class);
        when(scheduler.getGlobalRegionScheduler()).thenReturn(global);

        Field loader = CultAPI.class.getDeclaredField("loader");
        loader.setAccessible(true);
        Object original = loader.get(CultAPI.INSTANCE);
        loader.set(
                CultAPI.INSTANCE,
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {PlatformLoader.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "getPlatformServer" -> server;
                            case "getScheduler" -> scheduler;
                            default -> method.invoke(original, args);
                        }));
        return () -> loader.set(CultAPI.INSTANCE, original);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private PunishmentManager punishments(String... commands) {
        when(config.getStringListElse(eq("Punishments"), any())).thenReturn((List) List.of(
                new LinkedHashMap<>(Map.of("checks", List.of("thresholdprobe"), "commands", List.of(commands)))));
        PunishmentManager punishments = new PunishmentManager(player, () -> now, ledger);
        punishments.reload(config);
        return punishments;
    }

    private void flag(PunishmentManager punishments) {
        punishments.handleViolation(check);
        punishments.handleAlert(player, "verbose", check);
    }

    /** The alerts sent so far, as text. */
    private List<String> alerts() {
        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeast(0)).sendMessage(sent.capture());
        return sent.getAllValues().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .toList();
    }
}
