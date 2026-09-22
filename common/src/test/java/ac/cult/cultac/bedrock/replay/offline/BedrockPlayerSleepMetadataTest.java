package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.bedrock.bridge.GeyserPlayerSleepMetadata;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import net.minecraft.world.phys.Vec3;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.junit.Test;

import static org.junit.Assert.*;

public class BedrockPlayerSleepMetadataTest {
    @Test public void capturedBedMetadataGatesTravelUntilPlayerFlagsClear() {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.x = 2145.5;
            player.y = 64;
            player.z = -1768.5;
            var processor = player.checkManager.getSimulationProcessor();
            var metadata = new EntityDataMap();
            metadata.put(EntityDataTypes.BED_POSITION, Vector3i.from(2146, 64, -1769));
            metadata.put(EntityDataTypes.PLAYER_FLAGS, (byte) 2);
            processor.applyAcknowledgedBedrockMetadata(null, null, null, null, null, null, null,
                    GeyserPlayerSleepMetadata.sleeping(metadata));
            assertTrue(processor.isBedrockSleepingStateObserved());
            var dimensions = new EntityDataMap();
            dimensions.put(EntityDataTypes.WIDTH, 0.2f);
            dimensions.put(EntityDataTypes.HEIGHT, 0.2f);
            processor.applyAcknowledgedBedrockMetadata(0.2f, 0.2f, null, null, null, null, null,
                    GeyserPlayerSleepMetadata.sleeping(dimensions));
            var frame = BedrockAuthInputFrame.builder(player.getUniqueId()).clientTick(18014)
                    .position(new Vec3(2146.5, 63.28623962402344, -1768.5))
                    .packetPosition(new Vec3(2146.5, 64.90625, -1768.5))
                    .delta(new Vec3(1, -0.7137603759765625, 0))
                    .rotation(-124.55238f, 41.045105f, -124.55238f).moveVector(0, 0).build();
            assertNull(processor.processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY));
            assertEquals(64, player.y, 0);
            assertEquals(2145.5, player.x, 0);
            for (int i = 1; i <= 20; i++) {
                var forged = BedrockAuthInputFrame.builder(player.getUniqueId()).clientTick(18014 + i)
                        .position(new Vec3(2146.5 + i * 10, 80, -1768.5))
                        .packetPosition(new Vec3(2146.5 + i * 10, 81.62, -1768.5))
                        .delta(new Vec3(10, 1, 0)).rotation(0, 0, 0).moveVector(1, 1).build();
                assertNull(processor.processBedrockAuthInputFrame(forged, BedrockPredictionTrigger.OFFLINE_REPLAY));
                var held = ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState.previousState(
                        processor.getCurrentPredictionCommit().carry());
                assertNotNull(held);
                assertEquals(2145.5, held.physicalFeetPosition().x(), 0);
                assertEquals(64, held.physicalFeetPosition().y(), 0);
                assertEquals(-1768.5, held.physicalFeetPosition().z(), 0);
                assertEquals(0, held.velocity().length(), 0);
            }
            metadata.clear();
            metadata.put(EntityDataTypes.PLAYER_FLAGS, (byte) 0);
            processor.applyAcknowledgedBedrockMetadata(null, null, null, null, null, null, null,
                    GeyserPlayerSleepMetadata.sleeping(metadata));
            assertFalse(processor.isBedrockSleepingStateObserved());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test public void actorPoseAndUnrelatedPlayerBitsDoNotReplaceSleepFlag() {
        var metadata = new EntityDataMap();
        metadata.setFlag(EntityFlag.SLEEPING, false);
        assertNull(GeyserPlayerSleepMetadata.sleeping(metadata));
        metadata.put(EntityDataTypes.PLAYER_FLAGS, (byte) 3);
        assertEquals(Boolean.TRUE, GeyserPlayerSleepMetadata.sleeping(metadata));
        metadata.put(EntityDataTypes.PLAYER_FLAGS, (byte) 1);
        assertEquals(Boolean.FALSE, GeyserPlayerSleepMetadata.sleeping(metadata));
    }
}
