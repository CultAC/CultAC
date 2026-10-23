package ac.cult.cultac.manager.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bridge.protocol.CommandRoots;
import ac.cult.cultac.command.CloudCommandService;
import ac.grim.grimac.api.config.ConfigManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandRootsResolutionTest {
    private final List<String> warnings = new ArrayList<>();

    @Test
    void theConfiguredListIsTrimmedLowerCasedAndDeduplicated() {
        assertEquals(List.of("guard", "gd"), resolve(configured(" Guard", "GD ", "guard")));
        assertEquals(List.of("guard"), resolve(configured("guard")));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void anAbsentKeyOrConfigGivesTheDefaultWithoutWarning() {
        assertEquals(CommandRoots.DEFAULT, resolve(absent()));
        assertEquals(CommandRoots.DEFAULT, resolve(null));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void anEmptyOrInvalidValueGivesTheDefaultWithAWarning() {
        assertEquals(CommandRoots.DEFAULT, resolve(configured()));
        assertEquals(CommandRoots.DEFAULT, resolve(configured("guard", "two words")));
        assertEquals(CommandRoots.DEFAULT, resolve(configured("guard", "/gd")));
        assertEquals(CommandRoots.DEFAULT, resolve(configured("guard", " ")));
        assertEquals(4, warnings.size());
    }

    @Test
    void theBundledMessagesCarryTheDefaultAliases() {
        for (String locale : List.of("de", "en", "es", "fr", "it", "ja", "nl", "pt", "ru", "tr", "zh")) {
            try (var input = getClass().getResourceAsStream("/messages/" + locale + ".yml")) {
                String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(text.contains("\ncommand-aliases: [cult, cultac, grim, grimac]"), locale);
            } catch (IOException failure) {
                throw new AssertionError(locale, failure);
            }
        }
    }

    @Test
    void anInputUnderAnUnregisteredRootRunsUnderThePrimaryRoot() {
        assertEquals("Grim alerts", CloudCommandService.onRegisteredRoot("Grim alerts"));
        assertEquals("cult alerts", CloudCommandService.onRegisteredRoot("guard alerts"));
        assertEquals("cult", CloudCommandService.onRegisteredRoot("guard"));
    }

    private List<String> resolve(ConfigManager config) {
        return BaseConfigManager.resolveCommandRoots(config, warnings::add);
    }

    private static ConfigManager configured(String... roots) {
        ConfigManager config = mock(ConfigManager.class);
        when(config.getStringListElse(eq(CommandRoots.KEY), any())).thenReturn(List.of(roots));
        return config;
    }

    private static ConfigManager absent() {
        ConfigManager config = mock(ConfigManager.class);
        when(config.getStringListElse(eq(CommandRoots.KEY), any())).thenAnswer(invocation -> invocation.getArgument(1));
        return config;
    }
}
