package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.aim.processor.AimProcessor;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.RotationUpdate;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class PlayerSearchSnapshotTest {
    @Test
    void sensitivityPublishesReadinessAndBothAxesTogether() {
        OfflineCultTestBootstrap.installConfig();
        AimProcessor aim = new AimProcessor(mock(CultPlayer.class));
        assertTrue(Double.isNaN(aim.sensitivitySnapshot().horizontal()));
        assertTrue(Double.isNaN(aim.sensitivitySnapshot().vertical()));
        RotationUpdate rotation = new RotationUpdate(null, null, 0, 0);
        var writer = CompletableFuture.runAsync(() -> {
            for (int i = 1; i <= 100000; i++) {
                aim.modeX = aim.modeY = 1;
                aim.sensitivityX = i;
                aim.sensitivityY = -i;
                aim.process(rotation);
            }
        });
        do {
            var snapshot = aim.sensitivitySnapshot();
            if (Double.isFinite(snapshot.horizontal())) assertEquals(-snapshot.horizontal(), snapshot.vertical());
            else assertTrue(Double.isNaN(snapshot.vertical()));
        } while (!writer.isDone());
        writer.join();
        var published = aim.sensitivitySnapshot();
        aim.sensitivityX = 0;
        aim.modeY = 0;
        assertSame(published, aim.sensitivitySnapshot());
        aim.process(rotation);
        assertTrue(Double.isNaN(aim.sensitivitySnapshot().vertical()));
    }
}
