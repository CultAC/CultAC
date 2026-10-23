package ac.cult.cultac.manager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Punishment groups select checks by case-insensitive substrings of the check or alternative name. */
class PunishmentCheckMatchingTest {
    @BeforeAll
    static void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    void groupsEnableEveryMatchingCheckExceptExclusions() {
        AbstractCheck simulation = check("Simulation", null);
        AbstractCheck antiKb = check("AntiKnockback", "AntiKB");
        AbstractCheck badPacketsA = check("BadPacketsA", null);
        AbstractCheck badPacketsN = check("BadPacketsN", "BadPacketsNAlt");
        AbstractCheck unnamed = check(null, "TimerAlt");
        AbstractCheck timer = check("Timer", null);
        CultPlayer player = mock(CultPlayer.class);
        doReturn(List.of(simulation, antiKb, badPacketsA, badPacketsN, unnamed, timer))
                .when(player)
                .getChecks();
        ConfigManager config = mock(ConfigManager.class);
        when(config.getBooleanElse(anyString(), anyBoolean())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getStringElse(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getStringListElse(eq("Punishments"), any())).thenReturn((List) List.of(
                group(List.of("SIMULATION", "antikb"), List.of("10:0 [alert]")),
                group(List.of("badpackets", "!BadPacketsN"), List.of("5:20 [log]")),
                group(List.of("timer"), List.of())));

        new PunishmentManager(player).reload(config);

        for (AbstractCheck check : List.of(simulation, antiKb, badPacketsA, badPacketsN, unnamed, timer))
            verify(check).setEnabled(false);
        for (AbstractCheck check : List.of(simulation, antiKb, badPacketsA, timer))
            verify(check, times(1)).setEnabled(true);
        verify(badPacketsN, never()).setEnabled(true);
        verify(unnamed, never()).setEnabled(true);
    }

    private static AbstractCheck check(String name, String alternativeName) {
        AbstractCheck check = mock(AbstractCheck.class);
        when(check.getCheckName()).thenReturn(name);
        when(check.getAlternativeName()).thenReturn(alternativeName);
        return check;
    }

    private static LinkedHashMap<String, Object> group(List<String> checks, List<String> commands) {
        return new LinkedHashMap<>(Map.of("checks", checks, "commands", commands));
    }
}
