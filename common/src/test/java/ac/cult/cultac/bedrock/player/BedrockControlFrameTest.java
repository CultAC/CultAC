package ac.cult.cultac.bedrock.player;

import static org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType.END_LOADING_SCREEN;
import static org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType.START_LOADING_SCREEN;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.utils.math.Vec3;
import java.util.UUID;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

public class BedrockControlFrameTest {
    private final UUID uuid = UUID.randomUUID();
    private final BedrockPlayerState state = new BedrockPlayerState(uuid);

    // A capture of a horse ridden while forward toggles every tick: each tick that takes a mount teleport repeats the
    // input of the tick before it, and the horse moves with that input.
    @Test
    public void aTickThatTakesATeleportKeepsTheInputBeforeIt() {
        var held = offer(1, 1.0F, false);
        var teleport = offer(2, 0.0F, true);
        assertSame(held, state.controlFrame(teleport));
        var next = offer(3, 0.0F, false);
        assertSame(next, state.controlFrame(next));
    }

    // A capture of a horse ridden into a nether portal: the screen's ticks report no input while the rider still
    // holds forward, and the first tick the horse moves again moves it with that forward input.
    @Test
    public void theTicksOfALoadingScreenKeepTheInputBeforeIt() {
        var held = offer(1, 1.0F, false);
        state.loadingScreen.changeDimension(null, null);
        assertTrue(state.loadingScreen.accept(START_LOADING_SCREEN, null));
        assertSame(held, state.controlFrame(offer(2, 0.0F, false)));
        assertSame(held, state.controlFrame(offer(3, 0.0F, true)));
        assertTrue(state.loadingScreen.accept(END_LOADING_SCREEN, null));
        var after = offer(4, 0.0F, false);
        assertSame(after, state.controlFrame(after));
    }

    private BedrockAuthInputFrame offer(long tick, float forward, boolean teleport) {
        var frame = BedrockAuthInputFrame.builder(uuid)
                .protocolVersion(2193)
                .clientTick(tick)
                .position(Vec3.ZERO)
                .rotation(0, 0, 0)
                .moveVector(0, forward)
                .reportedEndOfTickVelocity(Vec3.ZERO)
                .rawInputFlags(teleport ? 1L << PlayerAuthInputData.HANDLE_TELEPORT.ordinal() : 0)
                .build();
        state.offerAuthInputFrame(frame);
        return frame;
    }
}
