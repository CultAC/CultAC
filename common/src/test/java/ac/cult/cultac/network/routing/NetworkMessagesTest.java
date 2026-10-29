package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class NetworkMessagesTest {
    @Test
    void everyBundledMessagesFileExplainsTheDisabledNetwork() {
        for (String locale : List.of("de", "en", "es", "fr", "it", "ja", "nl", "pt", "ru", "tr", "zh")) {
            try (var input = getClass().getResourceAsStream("/messages/" + locale + ".yml")) {
                Map<?, ?> messages = new Yaml().load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                for (String key : List.of("network-disabled-command", "network-disabled-alerts")) {
                    String text = assertInstanceOf(String.class, messages.get(key), locale + " " + key);
                    assertTrue(text.startsWith("%prefix% "), locale + " " + key);
                }
            } catch (IOException failure) {
                throw new AssertionError(locale, failure);
            }
        }
    }

    @Test
    void everyBundledMessagesFileNamesTheAlertScope() {
        for (String locale : List.of("de", "en", "es", "fr", "it", "ja", "nl", "pt", "ru", "tr", "zh")) {
            try (var input = getClass().getResourceAsStream("/messages/" + locale + ".yml")) {
                Map<?, ?> messages = new Yaml().load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                for (String key : List.of(
                        "alerts-scope",
                        "alerts-scope-set",
                        "alerts-scope-chosen",
                        "alerts-scope-default",
                        "alerts-scope-local")) {
                    assertInstanceOf(String.class, messages.get(key), locale + " " + key);
                }
                String scope = (String) messages.get("alerts-scope");
                assertTrue(scope.contains("%scope%") && scope.contains("%source%"), locale);
            } catch (IOException failure) {
                throw new AssertionError(locale, failure);
            }
        }
    }

    @Test
    void everyBundledMessagesFileHasTheRoutedCommandMessagesWithTheirPlaceholders() {
        Map<String, List<String>> placeholders = Map.ofEntries(
                Map.entry("network-command-same-server", List.of("%player%", "%location%")),
                Map.entry("network-spectate-unreachable", List.of("%player%", "%location%")),
                Map.entry("network-command-stopped", List.of("%instance%")),
                Map.entry("network-command-not-forwarded", List.of("%instance%")),
                Map.entry("network-command-target-left", List.of("%player%", "%instance%")),
                Map.entry("network-command-failed", List.of("%instance%", "%reason%")),
                Map.entry("network-command-line-too-large", List.of()),
                Map.entry("network-command-output-lost", List.of("%instance%")),
                Map.entry("network-command-unfinished", List.of("%instance%")),
                Map.entry("network-command-no-answer", List.of("%instance%")),
                Map.entry("network-command-output-cut", List.of("%instance%", "%lines%", "%kb%")));
        for (String locale : List.of("de", "en", "es", "fr", "it", "ja", "nl", "pt", "ru", "tr", "zh")) {
            try (var input = getClass().getResourceAsStream("/messages/" + locale + ".yml")) {
                Map<?, ?> messages = new Yaml().load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                for (var expected : placeholders.entrySet()) {
                    String where = locale + " " + expected.getKey();
                    String text = assertInstanceOf(String.class, messages.get(expected.getKey()), where);
                    for (String placeholder : expected.getValue()) assertTrue(text.contains(placeholder), where);
                }
            } catch (IOException failure) {
                throw new AssertionError(locale, failure);
            }
        }
    }
}
