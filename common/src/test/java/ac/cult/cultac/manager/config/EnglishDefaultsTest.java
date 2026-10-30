package ac.cult.cultac.manager.config;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Every key the plugin reads from the shipped English files resolves. */
class EnglishDefaultsTest {
    private static final List<String> MESSAGE_STRINGS = List.of(
            "alerts-format",
            "alerts-format-proxy",
            "experimental-symbol",
            "info-button-all",
            "info-button-all-hover",
            "info-button-group",
            "info-button-group-hover",
            "info-did-you-mean",
            "info-entry",
            "info-entry-hover",
            "info-entry-separator",
            "info-experimental",
            "info-experimental-note",
            "info-group",
            "info-group-header",
            "info-group-hover",
            "info-groups-header",
            "info-handling-alerts",
            "info-handling-automatic",
            "info-handling-none",
            "info-handling-off",
            "info-handling-recorded",
            "info-punishment",
            "info-punishment-alert",
            "info-punishment-and",
            "info-punishment-ban",
            "info-punishment-first",
            "info-punishment-kick",
            "info-punishments-logged",
            "info-punishments-none",
            "info-punishments-off",
            "info-punishments-webhook",
            "info-see-all",
            "info-unknown");

    @Test
    void everyMessageKeyResolves() {
        for (String key : MESSAGE_STRINGS)
            assertFalse(EnglishDefaults.string("messages", key).isEmpty(), key);
        assertFalse(EnglishDefaults.stringList("messages", "info-check").isEmpty());
    }

    @Test
    void everyDiscordKeyResolves() {
        assertFalse(EnglishDefaults.stringList("discord", "violation-content").isEmpty());
    }
}
