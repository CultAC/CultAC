package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.info.CheckCatalog;
import ac.cult.cultac.checks.info.CheckPolicy;
import ac.cult.cultac.checks.info.CheckTexts;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CheckInfoLinesTest {
    private static final CheckCatalog.Entry SPRINT_A = CheckCatalog.find("SprintA");
    private static final CheckCatalog.Entry SPRINT_D = CheckCatalog.find("SprintD");
    private static final CheckCatalog.Entry TIMER = CheckCatalog.find("Timer");

    @Test
    void saysAStaffOnlyCheckAlertsWithoutPunishing() {
        CheckInfoLines.View view = view(List.of("10:5 [alert]", "1:1 [log]"), false, true);
        assertEquals(
                "&eStaff alerts from 10 flags &8(flags from related checks add up over 5m) &8· &7no automatic"
                        + " punishment",
                CheckInfoLines.status(view, SPRINT_A));
        assertTrue(CheckInfoLines.advice(view, SPRINT_A).startsWith("One flag alone is not proof."));
    }

    @Test
    void namesEachCommandWithItsThreshold() {
        CheckInfoLines.View view = view(List.of("5:5 [alert]", "20:0 ban %player%", "10:5 say hi"), false, true);
        assertEquals(
                "&eStaff alerts from 5 flags&7, &c/ban &7runs at &f20 &7flags&7, &c/say &7runs at &f10 &7flags"
                        + " &8(flags from related checks add up over 5m)",
                CheckInfoLines.status(view, SPRINT_A));
        assertTrue(CheckInfoLines.advice(view, SPRINT_A).startsWith("The server runs the commands above by itself."));
    }

    @Test
    void saysWhereFlagsGoWithoutAlerts() {
        assertEquals(
                "&7Recorded in the player's history, no staff alerts",
                CheckInfoLines.status(view(List.of("1:1 [log]"), false, true), SPRINT_A));
        assertEquals(
                "&7Sent to the staff webhook, no staff alerts in game",
                CheckInfoLines.status(view(List.of("1:1 [webhook]"), false, true), SPRINT_A));
    }

    @Test
    void saysNothingIsSetUpForACheckInNoGroup() {
        CheckInfoLines.View view = view(List.of("10:5 [alert]"), false, true);
        assertEquals("&8No alerts or commands are set up for it here", CheckInfoLines.status(view, TIMER));
        assertEquals("Nothing. This server does not alert staff about it.", CheckInfoLines.advice(view, TIMER));
    }

    @Test
    void saysNothingIsPunishedInObserveOnlyMode() {
        CheckInfoLines.View view = view(List.of("20:0 ban %player%"), true, true);
        assertTrue(CheckInfoLines.status(view, SPRINT_A).endsWith("(observe-only mode: nothing is punished now)"));
        assertTrue(CheckInfoLines.advice(view, SPRINT_A).startsWith("Nothing is punished automatically right now."));
    }

    @Test
    void warnsThatAnExperimentalCheckIsNotProofOnItsOwn() {
        CheckInfoLines.View view = view(List.of("20:0 ban %player%"), false, true);
        assertTrue(CheckInfoLines.advice(view, SPRINT_D).startsWith("This check is still being tested"));
    }

    @Test
    void saysAnExperimentalCheckIsOffWhenExperimentalChecksAre() {
        CheckInfoLines.View view = view(List.of("5:5 [alert]"), false, false);
        assertEquals("&8Off, because experimental checks are turned off", CheckInfoLines.status(view, SPRINT_D));
        assertEquals("Nothing. Experimental checks are turned off here.", CheckInfoLines.advice(view, SPRINT_D));
    }

    @Test
    void findsACheckByTheNameThisServerGaveIt() {
        ConfigManager config = config();
        Mockito.when(config.getStringElse("SprintD.displayname", "SprintD")).thenReturn("BlindSprint");
        CheckInfoLines.View view =
                new CheckInfoLines.View(config, CheckPolicy.parse(List.of()), CheckTexts.get(), false, true);
        assertEquals(SPRINT_D, view.find("blindsprint"));
    }

    @Test
    void showsWhatTheModeratorTypedAsPlainText() {
        OfflineCultTestBootstrap.installConfig();
        ConfigManager config = config();
        Mockito.when(config.getStringElse(Mockito.eq("info-unknown"), Mockito.anyString()))
                .thenReturn("&cNo check is called &f%query%");
        Mockito.when(config.getStringElse(Mockito.eq("info-see-all"), Mockito.anyString()))
                .thenReturn("&7See every check.");
        CheckInfoLines.View view =
                new CheckInfoLines.View(config, CheckPolicy.parse(List.of()), CheckTexts.get(), false, true);
        String query = "&k%prefix%<red>\\'\"";
        List<Component> lines = CheckInfoLines.unknown(Mockito.mock(Sender.class), view, query);
        assertEquals(
                "No check is called " + query,
                PlainTextComponentSerializer.plainText().serialize(lines.getFirst()));
    }

    @Test
    void linksTheCheckNameInAnAlertByItsCanonicalName() {
        OfflineCultTestBootstrap.installConfig();
        Component alert = MessageUtil.miniMessage("&fSteve <click:suggest_command:'/tp Steve'>&bfailed</click> "
                + "<hover:show_text:'Ground Speed'>&fGround Speed*</hover> &f(x3) &7Ground Speedy");

        Component linked = CultInfo.linkCheckName(alert, check("Speed", "Ground Speed"));

        StringBuilder text = new StringBuilder();
        linkedText(linked, null, ClickEvent.runCommand(CloudCommandService.command("info check Speed")), text);
        assertEquals("Ground Speed", text.toString());
        StringBuilder suggested = new StringBuilder();
        linkedText(linked, null, ClickEvent.suggestCommand("/tp Steve"), suggested);
        assertEquals("failed", suggested.toString());
    }

    @Test
    void leavesAnAlertForACheckItCannotExplainAlone() {
        OfflineCultTestBootstrap.installConfig();
        Component alert = Component.text("SomeoneElsesCheck");
        assertEquals(alert, CultInfo.linkCheckName(alert, check("SomeoneElsesCheck", "SomeoneElsesCheck")));
    }

    private static Check check(String name, String displayName) {
        Check check = Mockito.mock(Check.class);
        Mockito.when(check.getCheckName()).thenReturn(name);
        Mockito.when(check.getDisplayName()).thenReturn(displayName);
        return check;
    }

    /** Appends the text of {@code component} whose click, its own or inherited, is {@code command}. */
    private static void linkedText(Component component, ClickEvent inherited, ClickEvent command, StringBuilder text) {
        ClickEvent click = component.clickEvent() != null ? component.clickEvent() : inherited;
        if (component instanceof TextComponent part && command.equals(click)) {
            text.append(part.content());
        }
        for (Component child : component.children()) linkedText(child, click, command, text);
    }

    private static CheckInfoLines.View view(List<String> commands, boolean observeOnly, boolean experimentalChecks) {
        LinkedHashMap<String, Object> group = new LinkedHashMap<>(
                Map.of("checks", List.of("Sprint"), "remove-violations-after", 300, "commands", commands));
        return new CheckInfoLines.View(
                config(), CheckPolicy.parse(List.of(group)), CheckTexts.get(), observeOnly, experimentalChecks);
    }

    private static ConfigManager config() {
        ConfigManager config = Mockito.mock(ConfigManager.class);
        Mockito.when(config.getStringElse(Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return config;
    }
}
