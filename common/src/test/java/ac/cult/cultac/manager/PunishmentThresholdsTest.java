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
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PunishmentThresholdsTest {
    private final ConfigManager config = mock(ConfigManager.class);
    private final CultPlayer player = mock(CultPlayer.class);
    private final Check check;
    private long now = 1_000_000L;

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

    /**
     * Runs global tasks at once and dispatches punishment commands into {@code ran} while {@code reachable} says a
     * server can run them.
     */
    private AutoCloseable platform(List<String> ran, AtomicBoolean reachable) throws ReflectiveOperationException {
        PlatformServer server = mock(PlatformServer.class);
        when(server.dispatchCommand(any(), anyString(), any(), any(), anyDouble(), anyString()))
                .thenAnswer(invocation -> {
                    if (reachable.get()) ran.add(invocation.getArgument(1));
                    return reachable.get();
                });
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        when(global.run(any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
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
        PunishmentManager punishments = new PunishmentManager(player, () -> now);
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
