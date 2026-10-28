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
}
