package ac.cult.cultac.checks.info;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.info.CheckPolicy.Action;
import ac.cult.cultac.checks.info.CheckPolicy.Handling;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CheckPolicyTest {
    private static final CheckCatalog.Entry SPRINT_D = CheckCatalog.find("SprintD");
    private static final CheckCatalog.Entry KEEP_SPRINT = CheckCatalog.find("KeepSprint");
    private static final CheckCatalog.Entry ANTI_KB = CheckCatalog.find("AntiKB");
    private static final CheckCatalog.Entry BAD_PACKETS_A = CheckCatalog.find("BadPacketsA");
    private static final CheckCatalog.Entry BAD_PACKETS_V = CheckCatalog.find("BadPacketsV");
    private static final CheckCatalog.Entry TIMER = CheckCatalog.find("Timer");

    @Test
    void matchesNamesByContainedTextLikePunishments() {
        CheckPolicy policy = CheckPolicy.parse(List.of(group(List.of("Sprint"), List.of("5:5 [alert]"))));
        assertEquals(Handling.ALERTS, policy.status(SPRINT_D).handling(true));
        assertEquals(Handling.ALERTS, policy.status(KEEP_SPRINT).handling(true));
        assertEquals(Handling.NONE, policy.status(BAD_PACKETS_A).handling(true));
    }

    @Test
    void matchesTheAlternativeName() {
        CheckPolicy policy = CheckPolicy.parse(List.of(group(List.of("AntiKnockback"), List.of("5:5 [alert]"))));
        assertEquals(Handling.ALERTS, policy.status(ANTI_KB).handling(true));
    }

    @Test
    void matchesTheAlternativeNameARunningCheckReportsWhenItHasNone() {
        CheckPolicy policy = CheckPolicy.parse(List.of(group(List.of("unknown"), List.of("5:5 [alert]"))));
        assertEquals(Handling.ALERTS, policy.status(SPRINT_D).handling(true));
        assertEquals(Handling.NONE, policy.status(TIMER).handling(true));
    }

    @Test
    void leavesOutExcludedChecks() {
        CheckPolicy policy =
                CheckPolicy.parse(List.of(group(List.of("BadPackets", "!BadPacketsV"), List.of("20:20 [alert]"))));
        assertEquals(Handling.ALERTS, policy.status(BAD_PACKETS_A).handling(true));
        assertEquals(Handling.NONE, policy.status(BAD_PACKETS_V).handling(true));
    }

    @Test
    void readsAlertsCommandsAndRecordingWithTheirThresholds() {
        LinkedHashMap<String, Object> group = group(
                List.of("Sprint"),
                List.of(
                        "10:5 [alert]",
                        "5:5 [alert]",
                        "1:1 [log]",
                        "20:20 [webhook]",
                        "40:0 ban %player% cheating",
                        "30:10 /Kick %player%"));
        group.put("remove-violations-after", 120);
        CheckPolicy.Status status = CheckPolicy.parse(List.of(group)).status(SPRINT_D);
        assertEquals(Handling.AUTOMATIC, status.handling(true));
        assertEquals(5, status.alertAt());
        assertTrue(status.recorded());
        assertTrue(status.webhook());
        assertEquals(List.of(new Action("/ban", 40), new Action("/kick", 30)), status.actions());
        assertEquals(120, status.groups().getFirst().expireSeconds());
    }

    @Test
    void countsAProxyAlertAsAStaffAlert() {
        CheckPolicy.Status status = CheckPolicy.parse(List.of(group(List.of("Sprint"), List.of("8:8 [proxy]"))))
                .status(SPRINT_D);
        assertEquals(Handling.ALERTS, status.handling(true));
        assertEquals(8, status.alertAt());
    }

    @Test
    void callsAGroupThatOnlyLogsOrSendsToTheWebhookRecorded() {
        CheckPolicy.Status logged = CheckPolicy.parse(List.of(group(List.of("Sprint"), List.of("1:1 [log]"))))
                .status(SPRINT_D);
        assertEquals(Handling.RECORDED, logged.handling(true));
        assertNull(logged.alertAt());
        CheckPolicy.Status webhook = CheckPolicy.parse(List.of(group(List.of("Sprint"), List.of("1:1 [webhook]"))))
                .status(SPRINT_D);
        assertEquals(Handling.RECORDED, webhook.handling(true));
        assertFalse(webhook.recorded());
    }

    @Test
    void callsAGroupWithNoCommandsNone() {
        CheckPolicy policy = CheckPolicy.parse(List.of(group(List.of("Sprint"), List.of())));
        assertEquals(Handling.NONE, policy.status(SPRINT_D).handling(true));
    }

    @Test
    void reportsACheckThatDoesNotRunAsOff() {
        CheckPolicy policy = CheckPolicy.parse(List.of(group(List.of("Sprint"), List.of("1:0 ban %player%"))));
        assertEquals(Handling.OFF, policy.status(SPRINT_D).handling(false));
    }

    @Test
    void stopsAtTheFirstGroupPunishmentsCannotRead() {
        CheckPolicy policy = CheckPolicy.parse(List.of(
                group(List.of("BadPackets"), List.of("5:5 [alert]")),
                group(List.of("KeepSprint"), List.of("not a command")),
                group(List.of("SprintD"), List.of("5:5 [alert]"))));
        assertEquals(Handling.ALERTS, policy.status(BAD_PACKETS_A).handling(true));
        assertEquals(Handling.NONE, policy.status(KEEP_SPRINT).handling(true));
        assertEquals(Handling.NONE, policy.status(SPRINT_D).handling(true));
    }

    @Test
    void stopsWhereALoadingCastFails() {
        LinkedHashMap<String, Object> quotedWindow = group(List.of("KeepSprint"), List.of("5:5 [alert]"));
        quotedWindow.put("remove-violations-after", "300");
        CheckPolicy policy = CheckPolicy.parse(List.of(
                group(List.of("BadPackets"), List.of("5:5 [alert]")),
                quotedWindow,
                group(List.of("SprintD"), List.of("5:5 [alert]"))));
        assertEquals(Handling.ALERTS, policy.status(BAD_PACKETS_A).handling(true));
        assertEquals(Handling.NONE, policy.status(KEEP_SPRINT).handling(true));
        assertEquals(Handling.NONE, policy.status(SPRINT_D).handling(true));

        CheckPolicy notAMap =
                CheckPolicy.parse(List.of("BadPackets", group(List.of("SprintD"), List.of("5:5 [alert]"))));
        assertEquals(Handling.NONE, notAMap.status(SPRINT_D).handling(true));

        CheckPolicy numberSelector = CheckPolicy.parse(
                List.of(group(List.of(7), List.of("5:5 [alert]")), group(List.of("SprintD"), List.of("5:5 [alert]"))));
        assertEquals(Handling.NONE, numberSelector.status(SPRINT_D).handling(true));
    }

    @Test
    void keepsOnlyCommandNameCharactersInAnAction() {
        CheckPolicy policy = CheckPolicy.parse(List.of(group(List.of("Sprint"), List.of("5:0 &cb<red>an %player%"))));
        assertEquals(List.of(new Action("/cbredan", 5)), policy.status(SPRINT_D).actions());
    }

    private static LinkedHashMap<String, Object> group(List<?> checks, List<String> commands) {
        return new LinkedHashMap<>(Map.of("checks", checks, "commands", commands));
    }
}
