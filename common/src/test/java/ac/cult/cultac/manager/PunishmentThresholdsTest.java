package ac.cult.cultac.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private PunishmentManager punishments(String... commands) {
        when(config.getStringListElse(eq("Punishments"), any())).thenReturn((List) List.of(new LinkedHashMap<>(
                Map.of("checks", List.of("thresholdprobe"), "commands", List.of(commands)))));
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
