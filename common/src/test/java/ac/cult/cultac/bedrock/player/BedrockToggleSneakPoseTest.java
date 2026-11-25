package ac.cult.cultac.bedrock.player;

import static org.junit.Assert.assertEquals;

import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

public final class BedrockToggleSneakPoseTest {
    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    public void toggledSneakFollowsItsStartAndStopRatherThanTheSneakButton() {
        // Flag 105, ticks 3797-3808: a sneak toggled on, the button released, then toggled off with the button held.
        BedrockPlayerState state = new BedrockPlayerState(PLAYER);
        expect(state, 3797, false, PlayerAuthInputData.PERSIST_SNEAK);
        expect(
                state,
                3798,
                true,
                PlayerAuthInputData.SNEAKING,
                PlayerAuthInputData.SNEAK_DOWN,
                PlayerAuthInputData.WANT_DOWN,
                PlayerAuthInputData.SNEAK_TOGGLE_DOWN,
                PlayerAuthInputData.PERSIST_SNEAK,
                PlayerAuthInputData.START_SNEAKING,
                PlayerAuthInputData.SNEAK_PRESSED_RAW,
                PlayerAuthInputData.SNEAK_CURRENT_RAW);
        expect(
                state,
                3801,
                true,
                PlayerAuthInputData.SNEAKING,
                PlayerAuthInputData.SNEAK_DOWN,
                PlayerAuthInputData.WANT_DOWN,
                PlayerAuthInputData.PERSIST_SNEAK,
                PlayerAuthInputData.SNEAK_RELEASED_RAW);
        expect(
                state,
                3805,
                true,
                PlayerAuthInputData.SNEAKING,
                PlayerAuthInputData.SNEAK_DOWN,
                PlayerAuthInputData.WANT_DOWN,
                PlayerAuthInputData.PERSIST_SNEAK);
        expect(
                state,
                3806,
                false,
                PlayerAuthInputData.SNEAK_TOGGLE_DOWN,
                PlayerAuthInputData.PERSIST_SNEAK,
                PlayerAuthInputData.STOP_SNEAKING,
                PlayerAuthInputData.SNEAK_PRESSED_RAW,
                PlayerAuthInputData.SNEAK_CURRENT_RAW);
        expect(
                state,
                3807,
                false,
                PlayerAuthInputData.SNEAK_TOGGLE_DOWN,
                PlayerAuthInputData.PERSIST_SNEAK,
                PlayerAuthInputData.SNEAK_CURRENT_RAW);
        expect(state, 3808, false, PlayerAuthInputData.PERSIST_SNEAK, PlayerAuthInputData.SNEAK_RELEASED_RAW);
    }

    private static void expect(BedrockPlayerState state, long tick, boolean sneaking, PlayerAuthInputData... held) {
        Set<PlayerAuthInputData> inputData = EnumSet.noneOf(PlayerAuthInputData.class);
        inputData.addAll(Set.of(held));
        long low = 0L;
        long high = 0L;
        for (PlayerAuthInputData input : inputData) {
            if (input.ordinal() < Long.SIZE) {
                low |= 1L << input.ordinal();
            } else {
                high |= 1L << (input.ordinal() - Long.SIZE);
            }
        }
        BedrockAuthInputFrame frame = BedrockAuthInputFrame.builder(PLAYER)
                .clientTick(tick)
                .rawInputFlags(low)
                .rawInputFlagsHigh(high)
                .sneaking(BedrockAuthInputFrame.holdsSneak(inputData::contains))
                .startSneaking(inputData.contains(PlayerAuthInputData.START_SNEAKING))
                .stopSneaking(inputData.contains(PlayerAuthInputData.STOP_SNEAKING))
                .build();
        state.recordProcessedAuthInputFrame(frame, null);
        assertEquals("sneaking after tick " + tick, sneaking, state.getClientPoseState(null).sneaking());
    }
}
