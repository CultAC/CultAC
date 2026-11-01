package ac.cult.cultac.utils.data.webhook.discord;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CompiledDiscordTemplateTest {
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
