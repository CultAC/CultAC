package ac.cult.cultac.manager;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.grim.grimac.api.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscordManagerTest {
    @Test
    void reloadUsesEachEmbedUrlSettingIndependently() throws ReflectiveOperationException {
        Map<String, Object> settings = Map.of(
                "enabled", true,
                "embed-image-url", "https://example.com/image.png",
                "embed-thumbnail-url", "https://example.com/thumbnail.png",
                "embed-footer-url", "https://example.com/footer.png"
        );
        ConfigManager config = Mockito.mock(ConfigManager.class, invocation -> {
            if (invocation.getMethod().getName().endsWith("Else")) {
                return settings.getOrDefault(invocation.getArgument(0), invocation.getArgument(1));
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        });

        BaseConfigManager base = new BaseConfigManager();
        base.load(config);
        Field activeConfig = CultAPI.class.getDeclaredField("configManager");
        activeConfig.setAccessible(true);
        Object previous = activeConfig.get(CultAPI.INSTANCE);
        try {
            activeConfig.set(CultAPI.INSTANCE, base);
            DiscordManager manager = new DiscordManager();
            manager.reload();

            assertAll(
                    () -> assertEquals("https://example.com/image.png", url(manager, "embedImageUrl")),
                    () -> assertEquals("https://example.com/thumbnail.png", url(manager, "embedThumbnailUrl")),
                    () -> assertEquals("https://example.com/footer.png", url(manager, "embedFooterUrl"))
            );
        } finally {
            activeConfig.set(CultAPI.INSTANCE, previous);
        }
    }

    private static String url(DiscordManager manager, String name) throws ReflectiveOperationException {
        Field field = DiscordManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(manager);
    }
}
