package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.info.CheckCatalog;
import ac.cult.cultac.checks.info.CheckCategory;
import ac.cult.cultac.checks.info.CheckPolicy;
import ac.cult.cultac.checks.info.CheckPolicy.Handling;
import ac.cult.cultac.checks.info.CheckTexts;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CheckInfoLinesTest {
    private static final CheckCatalog.Entry SPRINT_A = CheckCatalog.find("SprintA");
    private static final CheckCatalog.Entry SPRINT_D = CheckCatalog.find("SprintD");
    private static final CheckCatalog.Entry TIMER = CheckCatalog.find("Timer");

    @BeforeAll
    static void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    void alertsWithoutCommandsListOnlyTheAlert() {
        assertEquals("Alert at 10 flags", CheckInfoLines.punishments(view("10:5 [alert]", "1:1 [log]"), SPRINT_A));
    }

    @Test
    void punishmentsAreListedByFlagCount() {
        assertEquals(
                "Alert at 5 flags&7, &f/say at 10 flags&7, &fban at 20 flags",
                CheckInfoLines.punishments(view("5:5 [alert]", "20:0 ban %player%", "10:5 say hi"), SPRINT_A));
    }

    @Test
    void theFirstFlagIsSaidInWords() {
        assertEquals(
                "Alert and kick on the first flag",
                CheckInfoLines.punishments(view("1:1 [alert]", "1:0 kick %player%"), SPRINT_A));
    }

    @Test
    void groupsMergeIntoOneListWithTheLowestAlert() {
        CheckInfoLines.View view = viewOf(
                group("10:5 [alert]", "40:0 kick %player%"),
                group("5:5 [alert]", "20:0 ban %player%"),
                group("100:40 [alert]"));
        assertEquals(
                "Alert at 5 flags&7, &fban at 20 flags&7, &fkick at 40 flags",
                CheckInfoLines.punishments(view, SPRINT_A));
    }

    @Test
    void anyBanCommandReadsAsOneBanAtItsFirstCount() {
        assertEquals(
                "Alert on the first flag&7, &fban at 5 flags",
                CheckInfoLines.punishments(
                        viewOf(
                                group("1:1 [alert]", "5:0 dbanraw %player% reason"),
                                group("15:0 litebans:tempban %player% 7d")),
                        SPRINT_A));
    }

    @Test
    void logAndWebhookSayWhereTheFlagsGo() {
        assertEquals("Logged to their history only", CheckInfoLines.punishments(view("1:1 [log]"), SPRINT_A));
        assertEquals("Sent to Discord only", CheckInfoLines.punishments(view("1:1 [webhook]"), SPRINT_A));
    }

    @Test
    void aCheckInNoGroupHasNone() {
        assertEquals("None", CheckInfoLines.punishments(view("10:5 [alert]"), TIMER));
    }

    @Test
    void anExperimentalCheckIsOffWhileExperimentalChecksAreOff() {
        assertEquals(
                "None, experimental checks are off", CheckInfoLines.punishments(view(false, "5:5 [alert]"), SPRINT_D));
    }

    @Test
    void aGroupListsItsChecksByHandling() {
        CheckInfoLines.View view = view(false, "20:0 ban %player%");
        Map<Handling, List<CheckCatalog.Entry>> byHandling =
                CheckInfoLines.byHandling(view, List.of(TIMER, SPRINT_D, SPRINT_A));
        assertEquals(List.of(Handling.AUTOMATIC, Handling.NONE, Handling.OFF), List.copyOf(byHandling.keySet()));
        assertEquals(List.of(SPRINT_A), byHandling.get(Handling.AUTOMATIC));
        assertEquals(List.of(TIMER), byHandling.get(Handling.NONE));
        assertEquals(List.of(SPRINT_D), byHandling.get(Handling.OFF));
    }

    @Test
    void aGroupLabelsEachHandlingInPlainWords() {
        List<String> lines = groupLines();
        assertEquals("Auto-punish: NoFall", lines.get(1));
        assertTrue(lines.get(2).startsWith("Alerts only: ") && lines.get(2).contains("Timer"), lines.get(2));
        assertTrue(lines.get(3).startsWith("No alerts or punishment: "), lines.get(3));
        assertEquals("* Experimental (still in testing)", lines.getLast());
    }

    @Test
    void didYouMeanLeavesOutTheExperimentalMark() {
        ConfigManager config = config();
        when(config.getStringElse(eq("info-unknown"), anyString())).thenReturn("&cUnknown check: &f%query%");
        List<Component> lines = CheckInfoLines.unknown(mock(Sender.class), view(config), "sprintd");
        assertEquals(
                "Did you mean: SprintD",
                PlainTextComponentSerializer.plainText().serialize(lines.get(1)));
    }

    @Test
    void aCheckIsFoundByItsDisplayName() {
        ConfigManager config = config();
        when(config.getStringElse("SprintD.displayname", "SprintD")).thenReturn("BlindSprint");
        assertEquals(SPRINT_D, view(config).find("blindsprint"));
    }

    @Test
    void anUnknownQueryIsShownAsPlainText() {
        ConfigManager config = config();
        // The default %prefix% and %command% need the platform's placeholder manager.
        when(config.getStringElse(eq("info-unknown"), anyString())).thenReturn("&cUnknown check: &f%query%");
        when(config.getStringElse(eq("info-see-all"), anyString())).thenReturn("&7See every check.");
        String query = "&k%prefix%<red>\\'\"";
        List<Component> lines = CheckInfoLines.unknown(mock(Sender.class), view(config), query);
        assertEquals(
                "Unknown check: " + query,
                PlainTextComponentSerializer.plainText().serialize(lines.getFirst()));
    }

    private static List<String> groupLines() {
        ConfigManager config = config();
        when(config.getStringElse(eq("info-group-header"), anyString())).thenReturn("%category% checks");
        LinkedHashMap<String, Object> alerts = group("5:5 [alert]");
        alerts.put("checks", List.of("Timer"));
        LinkedHashMap<String, Object> kicks = group("5:0 kick %player%");
        kicks.put("checks", List.of("NoFall"));
        CheckInfoLines.View view =
                new CheckInfoLines.View(config, CheckPolicy.parse(List.of(alerts, kicks)), CheckTexts.get(), true);
        List<String> lines = new ArrayList<>();
        for (Component line : CheckInfoLines.group(mock(Sender.class), view, CheckCategory.MOVEMENT)) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(line));
        }
        return lines;
    }

    @SafeVarargs
    private static CheckInfoLines.View viewOf(Map<String, Object>... groups) {
        return new CheckInfoLines.View(config(), CheckPolicy.parse(List.of(groups)), CheckTexts.get(), true);
    }

    private static LinkedHashMap<String, Object> group(String... commands) {
        return new LinkedHashMap<>(Map.of("checks", List.of("Sprint"), "commands", List.of(commands)));
    }

    private static CheckInfoLines.View view(String... commands) {
        return view(true, commands);
    }

    private static CheckInfoLines.View view(boolean experimentalChecks, String... commands) {
        return new CheckInfoLines.View(
                config(), CheckPolicy.parse(List.of(group(commands))), CheckTexts.get(), experimentalChecks);
    }

    private static CheckInfoLines.View view(ConfigManager config) {
        return new CheckInfoLines.View(config, CheckPolicy.parse(List.of()), CheckTexts.get(), true);
    }

    private static ConfigManager config() {
        ConfigManager config = mock(ConfigManager.class);
        when(config.getStringElse(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        return config;
    }
}
