package ac.cult.cultac.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CheckNameLinkTest {
    @Test
    void linksTheCheckNameInAnAlertByItsCanonicalName() {
        OfflineCultTestBootstrap.installConfig();
        Component alert = MessageUtil.miniMessage("&fSteve <click:suggest_command:'/tp Steve'>&bfailed</click> "
                + "<hover:show_text:'Ground Speed'>&fGround Speed*</hover> &f(x3) &7Ground Speedy Ground Speed_2");

        Component linked = CheckNameLink.link(alert, check("Speed", "Ground Speed"));

        StringBuilder text = new StringBuilder();
        linkedText(linked, null, ClickEvent.runCommand(CloudCommandService.command("info check Speed")), text);
        assertEquals("Ground Speed", text.toString());
        StringBuilder suggested = new StringBuilder();
        linkedText(linked, null, ClickEvent.suggestCommand("/tp Steve"), suggested);
        assertEquals("failed", suggested.toString());
    }

    @Test
    void linksEveryAlertForTheSameCheck() {
        OfflineCultTestBootstrap.installConfig();
        Check check = check("Speed", "Ground Speed");
        ClickEvent command = ClickEvent.runCommand(CloudCommandService.command("info check Speed"));
        for (String flags : new String[] {"(x1)", "(x2)"}) {
            StringBuilder text = new StringBuilder();
            linkedText(
                    CheckNameLink.link(MessageUtil.miniMessage("&fGround Speed " + flags), check), null, command, text);
            assertEquals("Ground Speed", text.toString());
        }
    }

    @Test
    void leavesAnAlertForACheckItCannotExplainAlone() {
        OfflineCultTestBootstrap.installConfig();
        Component alert = Component.text("SomeoneElsesCheck");
        assertEquals(alert, CheckNameLink.link(alert, check("SomeoneElsesCheck", "SomeoneElsesCheck")));
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
}
