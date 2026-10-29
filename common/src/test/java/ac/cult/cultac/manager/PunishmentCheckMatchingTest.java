package ac.cult.cultac.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.info.CheckCatalog;
import ac.cult.cultac.checks.info.CheckPolicy;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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

    @Test
    void checkPolicySelectsTheSameChecksAsPunishmentManager() {
        List<Object> groups = List.of(
                group(List.of("Sprint", "!SprintD"), List.of("5:5 [alert]")),
                group(List.of("unknown", "antikb"), List.of("5:5 [alert]")),
                group(List.of("BadPackets", "BadPacketsV", "!BadPacketsV"), List.of("5:5 [alert]")));
        Set<String> enabled = new TreeSet<>();
        List<AbstractCheck> checks = new ArrayList<>();
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) {
            AbstractCheck check = check(entry.name(), entry.altName());
            doAnswer(invocation -> {
                        if (invocation.<Boolean>getArgument(0)) enabled.add(entry.name());
                        return null;
                    })
                    .when(check)
                    .setEnabled(anyBoolean());
            checks.add(check);
        }
        CultPlayer player = mock(CultPlayer.class);
        doReturn(checks).when(player).getChecks();
        ConfigManager config = mock(ConfigManager.class);
        when(config.getBooleanElse(anyString(), anyBoolean())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getStringElse(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getStringListElse(eq("Punishments"), any())).thenReturn((List) groups);

        new PunishmentManager(player).reload(config);

        CheckPolicy policy = CheckPolicy.parse(groups);
        Set<String> held = new TreeSet<>();
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) {
            if (!policy.status(entry).groups().isEmpty()) held.add(entry.name());
        }
        assertTrue(held.containsAll(List.of("SprintA", "AntiKB", "BadPacketsV")), held.toString());
        assertEquals(enabled, held);
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
