package ac.cult.cultac.utils.data.webhook.discord;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CompiledDiscordTemplateTest {
    @Test
    void unknownSensitivityHasNoPercentageSuffix() {
        var template =
                CompiledDiscordTemplate.compile("**xsens**: `%h_sensitivity%%` **ysens**: `%v_sensitivity_raw%`");
        assertEquals(
                "**xsens**: `n/a` **ysens**: `n/a`",
                template.render(null, Map.of("%h_sensitivity%", "n/a", "%v_sensitivity_raw%", "n/a"), Map.of(), 'x'));
        assertEquals(
                "**xsens**: `100%` **ysens**: `0.50000`",
                template.render(
                        null, Map.of("%h_sensitivity%", "100", "%v_sensitivity_raw%", "0.50000"), Map.of(), 'x'));
    }

    @Test
    void codeBlockReplacesBackticks() {
        assertEquals("```\naˋˋˋb\n```", CompiledDiscordTemplate.codeBlock("a```b", 'ˋ'));
    }

    @Test
    void codeBlockWithoutReplacementCannotCloseTheFence() {
        String block = CompiledDiscordTemplate.codeBlock("a````b", '`');
        String body = block.substring(4, block.length() - 4);
        assertFalse(body.contains("``"));
        assertEquals("a````b", body.replace("​", ""));
    }
}
