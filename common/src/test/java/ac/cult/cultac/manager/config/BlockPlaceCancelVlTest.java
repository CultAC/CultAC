package ac.cult.cultac.manager.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.checks.impl.scaffolding.FarPlace;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.player.CultPlayer;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A block place check reads its cancel threshold from the lower-case {@code cancelvl} key the bundled configs write. */
class BlockPlaceCancelVlTest {
    @TempDir
    Path directory;

    @Test
    void theConfiguredCancelVlIsRead() throws Exception {
        OfflinePlayers.installConfig();
        Files.writeString(directory.resolve("config.yml"), "FarPlace:\n    cancelvl: 2\n");
        File folder = directory.toFile();
        ConfigManagerFileImpl config = new ConfigManagerFileImpl(null) {
            @Override
            File getDataFolder() {
                return folder;
            }
        };
        config.reload();

        CultPlayer player = OfflinePlayers.player();
        try {
            FarPlace farPlace = player.checkManager.getCheck(FarPlace.class);
            farPlace.onReload(config);
            Field cancelVl = BlockPlaceCheck.class.getDeclaredField("cancelVL");
            cancelVl.setAccessible(true);
            assertEquals(2, cancelVl.getInt(farPlace));
        } finally {
            OfflinePlayers.close(player);
        }
    }
}
