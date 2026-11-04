package ac.cult.cultac.manager.config.update;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class MessageAlertUpgradeTest {
    @TempDir
    Path directory;

    @Test
    void existingOperatorAlertsKeepTheirWordingAndGainSpectate() throws Exception {
        String original =
                "%prefix% &r&f%player% &bhas been detected for <hover:show_text:'Check details'>%check_name%</hover> &7[%vl%]";
        Map<String, Object> updated = upgrade(original);
        for (String key : new String[] {"alerts-format", "alerts-format-proxy"}) {
            String format = (String) updated.get(key);
            assertTrue(format.contains("has been detected for"));
            assertTrue(format.contains("<hover:show_text:'Check details'>%check_name%</hover>"));
            Component rendered = MiniMessage.miniMessage()
                    .deserialize(format.replace("%command%", "gladiator").replace("%player%", "Alice"));
            assertTrue(hasSpectate(rendered));
        }
        assertEquals(4, updated.get("config-version"));
    }

    @Test
    void operatorClickActionsArePreserved() throws Exception {
        String format = "<click:run_command:'/custom %player%'>%player%</click>";
        assertEquals(format, upgrade(format).get("alerts-format"));
    }

    private static boolean hasSpectate(Component component) {
        if (component instanceof TextComponent text
                && text.content().contains("Alice")
                && ClickEvent.runCommand("/gladiator spectate Alice").equals(component.clickEvent())) {
            assertNotNull(component.hoverEvent());
            return true;
        }
        return component.children().stream().anyMatch(MessageAlertUpgradeTest::hasSpectate);
    }

    private Map<String, Object> upgrade(String format) throws Exception {
        Path file = directory.resolve("messages.yml");
        Files.writeString(
                file,
                new Yaml()
                        .dump(Map.of(
                                "config-version",
                                3,
                                "config-flavor",
                                "V2",
                                "alerts-format",
                                format,
                                "alerts-format-proxy",
                                format)));
        var result = new ConfigUpdater(CultConfigSpecs.class, Logger.getAnonymousLogger())
                .update(file.toFile(), CultConfigSpecs.messages());
        assertTrue(result.migrated);
        return new Yaml().load(Files.readString(file));
    }
}
