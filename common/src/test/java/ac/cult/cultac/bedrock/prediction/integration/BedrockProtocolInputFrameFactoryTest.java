package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockClientPoseState;
import java.util.Set;
import java.util.UUID;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class BedrockProtocolInputFrameFactoryTest {
    @Test
    public void sneakCurrentRawHighFlagReachesInputFrame() {
        long highFlags = 1L << (PlayerAuthInputData.SNEAK_CURRENT_RAW.ordinal() - Long.SIZE);
        BedrockAuthInputFrame authFrame = BedrockAuthInputFrame.builder(UUID.randomUUID())
                .rawInputFlagsHigh(highFlags)
                .build();

        var inputFrame = new BedrockProtocolInputFrameFactory().create(
                authFrame, 1L, BedrockClientPoseState.STANDING, false, Set.of());

        assertTrue(inputFrame.inputData().contains("SNEAK_CURRENT_RAW"));
    }

    @Test
    public void immobileActionEdgesReachTheInternalFrame() {
        long[] flags = rawFlags(
                PlayerAuthInputData.START_FLYING,
                PlayerAuthInputData.STOP_FLYING,
                PlayerAuthInputData.START_SNEAKING,
                PlayerAuthInputData.STOP_SNEAKING);
        BedrockAuthInputFrame authFrame = BedrockAuthInputFrame.builder(UUID.randomUUID())
                .rawInputFlags(flags[0])
                .rawInputFlagsHigh(flags[1])
                .startCrawling(true)
                .stopCrawling(true)
                .build();

        var inputFrame = new BedrockProtocolInputFrameFactory().create(
                authFrame, 1L, BedrockClientPoseState.STANDING, false, Set.of());

        assertTrue(inputFrame.intent().fly().start());
        assertTrue(inputFrame.intent().fly().stop());
        assertTrue(inputFrame.intent().pose().startCrawling());
        assertTrue(inputFrame.intent().pose().stopCrawling());
        assertTrue(inputFrame.intent().pose().startSneaking());
        assertTrue(inputFrame.intent().pose().stopSneaking());
    }

    private static long[] rawFlags(PlayerAuthInputData... inputs) {
        long low = 0L;
        long high = 0L;
        for (PlayerAuthInputData input : inputs) {
            if (input.ordinal() < Long.SIZE) {
                low |= 1L << input.ordinal();
            } else {
                high |= 1L << (input.ordinal() - Long.SIZE);
            }
        }
        return new long[]{low, high};
    }
}
