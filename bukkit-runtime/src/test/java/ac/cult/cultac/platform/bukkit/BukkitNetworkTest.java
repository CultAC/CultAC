package ac.cult.cultac.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BukkitNetworkTest {
    @TempDir
    Path directory;

    private YamlConfiguration paperGlobal(String velocity) throws Exception {
        File file = directory.resolve("paper-global.yml").toFile();
        Files.writeString(file.toPath(), """
                _version: 29
                proxies:
                  bungee-cord:
                    online-mode: true
                  velocity:
                %s
                """.formatted(velocity.indent(4).stripTrailing()));
        return YamlConfiguration.loadConfiguration(file);
    }

    @Test
    void theForwardingSecretIsReadWhileVelocityForwardingIsOn() throws Exception {
        assertArrayEquals(
                "f0rwarding".getBytes(StandardCharsets.UTF_8),
                BukkitNetwork.velocitySecret(paperGlobal("enabled: true\nonline-mode: true\nsecret: f0rwarding")));
    }

    @Test
    void thereIsNoSecretWhileForwardingIsOffOrTheSecretIsEmpty() throws Exception {
        assertNull(BukkitNetwork.velocitySecret(paperGlobal("enabled: false\nonline-mode: true\nsecret: f0rwarding")));
        assertNull(BukkitNetwork.velocitySecret(paperGlobal("enabled: true\nonline-mode: true\nsecret: ''")));
        assertNull(BukkitNetwork.velocitySecret(null));
    }
}
