package ac.cult.cultac.manager.config.update;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
        assertEquals(5, updated.get("config-version"));
    }

    @Test
    void aV4FileGainsTheSuspectedFormatAndKeepsItsOwnAlerts() throws Exception {
        String format = "<click:run_command:'/custom %player%'>%player%</click> flagged";
        Path file = directory.resolve("messages.yml");
        Files.writeString(
                file, new Yaml().dump(Map.of("config-version", 4, "config-flavor", "V2", "alerts-format", format)));
        assertTrue(new ConfigUpdater(CultConfigSpecs.class, Logger.getAnonymousLogger())
                .update(file.toFile(), CultConfigSpecs.messages())
                .migrated);
        Map<String, Object> updated = new Yaml().load(Files.readString(file));

        assertEquals(format, updated.get("alerts-format"));
        String suspected = (String) updated.get("alerts-format-suspected");
        assertTrue(suspected.contains("is suspected of cheating!"), suspected);
        Component rendered = MiniMessage.miniMessage()
                .deserialize(suspected.replace("%command%", "gladiator").replace("%player%", "Alice"));
        assertTrue(hasSpectate(rendered));
        assertEquals(5, updated.get("config-version"));
    }

    @Test
    void messagesTheOperatorAddedSurviveTheUpgrade() throws Exception {
        Map<String, Object> updated = upgradeV4("en", Map.of("alerts-format-staff", "%player% needs a look"));
        assertEquals("%player% needs a look", updated.get("alerts-format-staff"));
        assertEquals(5, updated.get("config-version"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void messagesTheOperatorAddedInsideAShippedSectionSurviveTheUpgrade() throws Exception {
        Map<String, Object> updated = upgradeV4("en", Map.of("disconnect", Map.of("staff", "%player% needs a look")));
        Map<String, Object> disconnect = (Map<String, Object>) updated.get("disconnect");
        assertEquals("%player% needs a look", disconnect.get("staff"));
        assertTrue(disconnect.size() > 1, disconnect.toString());
    }

    @Test
    void aTranslatedFileKeepsTheOperatorsSuspectedFormat() throws Exception {
        Map<String, Object> updated = upgradeV4("de", Map.of("alerts-format-suspected", "%player% ist verdächtig"));
        assertEquals("%player% ist verdächtig", updated.get("alerts-format-suspected"));
        assertEquals(5, updated.get("config-version"));
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

    private Map<String, Object> upgradeV4(String language, Map<String, Object> messages) throws Exception {
        Path file = directory.resolve("messages.yml");
        Map<String, Object> data = new LinkedHashMap<>(messages);
        data.put("config-version", 4);
        data.put("config-flavor", "V2");
        Files.writeString(file, new Yaml().dump(data));
        var results = new ConfigUpdater(CultConfigSpecs.class, Logger.getAnonymousLogger())
                .updateAll(Map.of(file.toFile(), CultConfigSpecs.messages()), language);
        assertTrue(results.get(file.toFile()).migrated);
        return new Yaml().load(Files.readString(file));
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
