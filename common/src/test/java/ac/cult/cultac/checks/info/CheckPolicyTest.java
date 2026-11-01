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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CheckPolicyTest {
    private static final CheckCatalog.Entry SPRINT_D = CheckCatalog.find("SprintD");
    private static final CheckCatalog.Entry KEEP_SPRINT = CheckCatalog.find("KeepSprint");
    private static final CheckCatalog.Entry ANTI_KB = CheckCatalog.find("AntiKB");
    private static final CheckCatalog.Entry BAD_PACKETS_A = CheckCatalog.find("BadPacketsA");
    private static final CheckCatalog.Entry BAD_PACKETS_V = CheckCatalog.find("BadPacketsV");
    private static final CheckCatalog.Entry TIMER = CheckCatalog.find("Timer");

    @Test
    void aSelectorMatchesEveryCheckWhoseNameContainsIt() {
        CheckPolicy policy = policy(group(List.of("Sprint"), "5:5 [alert]"));
        assertEquals(Handling.ALERTS, handling(policy, SPRINT_D));
        assertEquals(Handling.ALERTS, handling(policy, KEEP_SPRINT));
        assertEquals(Handling.NONE, handling(policy, BAD_PACKETS_A));
    }

    @Test
    void aSelectorMatchesTheAltNameToo() {
        assertEquals(Handling.ALERTS, handling(policy(group(List.of("AntiKnockback"), "5:5 [alert]")), ANTI_KB));
    }

    @Test
    void unknownMatchesCheckDataChecksWithoutAnAltName() {
        // @CheckData reports a missing alt name as "UNKNOWN"; CheckInfo checks like Timer report null.
        CheckPolicy policy = policy(group(List.of("unknown"), "5:5 [alert]"));
        assertEquals(Handling.ALERTS, handling(policy, SPRINT_D));
        assertEquals(Handling.NONE, handling(policy, TIMER));
    }

    @Test
    void anExcludedCheckIsLeftOut() {
        CheckPolicy policy = policy(group(List.of("BadPackets", "!BadPacketsV"), "20:20 [alert]"));
        assertEquals(Handling.ALERTS, handling(policy, BAD_PACKETS_A));
        assertEquals(Handling.NONE, handling(policy, BAD_PACKETS_V));
    }

    @Test
    void everyKindOfCommandIsReadWithItsThreshold() {
        LinkedHashMap<String, Object> group = group(
                List.of("Sprint"),
                "10:5 [alert]",
                "5:5 [alert]",
                "1:1 [log]",
                "20:20 [webhook]",
                "40:0 ban %player% cheating",
                "30:10 /Kick %player%");
        CheckPolicy.Status status = policy(group).status(SPRINT_D);
        assertEquals(Handling.AUTOMATIC, status.handling(true));
        assertEquals(5, status.alertAt());
        assertTrue(status.recorded());
        assertTrue(status.webhook());
        assertEquals(List.of(new Action("/ban", 40), new Action("/kick", 30)), status.actions());
    }

    @Test
    void aProxyAlertIsAStaffAlert() {
        CheckPolicy.Status status =
                policy(group(List.of("Sprint"), "8:8 [proxy]")).status(SPRINT_D);
        assertEquals(Handling.ALERTS, status.handling(true));
        assertEquals(8, status.alertAt());
    }

    @Test
    void logOrWebhookAloneIsRecorded() {
        CheckPolicy.Status logged =
                policy(group(List.of("Sprint"), "1:1 [log]")).status(SPRINT_D);
        assertEquals(Handling.RECORDED, logged.handling(true));
        assertNull(logged.alertAt());
        CheckPolicy.Status webhook =
                policy(group(List.of("Sprint"), "1:1 [webhook]")).status(SPRINT_D);
        assertEquals(Handling.RECORDED, webhook.handling(true));
        assertFalse(webhook.recorded());
    }

    @Test
    void aGroupWithoutCommandsDoesNothing() {
        assertEquals(Handling.NONE, handling(policy(group(List.of("Sprint"))), SPRINT_D));
    }

    @Test
    void aCheckThatIsNotRunningIsOffEvenWithABan() {
        CheckPolicy policy = policy(group(List.of("Sprint"), "1:0 ban %player%"));
        assertEquals(Handling.OFF, policy.status(SPRINT_D).handling(false));
    }

    @ParameterizedTest
    @MethodSource("unloadableGroups")
    void loadingStopsAtTheFirstBadGroup(Object bad) {
        CheckPolicy policy =
                policy(group(List.of("BadPackets"), "5:5 [alert]"), bad, group(List.of("SprintD"), "5:5 [alert]"));
        assertEquals(Handling.ALERTS, handling(policy, BAD_PACKETS_A));
        assertEquals(Handling.NONE, handling(policy, KEEP_SPRINT));
        assertEquals(Handling.NONE, handling(policy, SPRINT_D));
    }

    static List<Object> unloadableGroups() {
        LinkedHashMap<String, Object> quotedWindow = group(List.of("KeepSprint"), "5:5 [alert]");
        quotedWindow.put("remove-violations-after", "300");
        return List.of(
                group(List.of("KeepSprint"), "not a command"),
                quotedWindow,
                "BadPackets",
                group(List.of(7), "5:5 [alert]"));
    }

    @Test
    void formattingInACommandNameIsDropped() {
        CheckPolicy policy = policy(group(List.of("Sprint"), "5:0 &cb<red>an %player%"));
        assertEquals(List.of(new Action("/cbredan", 5)), policy.status(SPRINT_D).actions());
    }

    private static CheckPolicy policy(Object... groups) {
        return CheckPolicy.parse(List.of(groups));
    }

    private static Handling handling(CheckPolicy policy, CheckCatalog.Entry check) {
        return policy.status(check).handling(true);
    }

    private static LinkedHashMap<String, Object> group(List<?> checks, String... commands) {
        return new LinkedHashMap<>(Map.of("checks", checks, "commands", List.of(commands)));
    }
}
