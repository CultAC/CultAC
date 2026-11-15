package ac.cult.cultac.bedrock.replay.offline;

import static org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType.END_LOADING_SCREEN;
import static org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType.START_LOADING_SCREEN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.integration.BedrockFrameProcessor;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.junit.Test;

/** A player in the open air whose inputs all report the place it started at. */
public class BedrockLoadingScreenHoldTest {
    private static final Vec3 START = new Vec3(1.5, 100, 1.5);
    private static final int INPUTS = 12;
    private static final Vec3 FALL = new Vec3(0, -0.0784, 0);

    @Test
    public void theJoinScreenStartedBeforeTheFirstInputHoldsTheActor() {
        Result result = stayInTheAir(0);
        assertTrue(result.started);
        assertEquals(INPUTS, result.accepted);
    }

    @Test
    public void aJoinScreenLeftUnstartedCannotHoldTheActorLater() {
        Result result = stayInTheAir(1);
        assertEquals("inputs accepted in the air", 0, result.accepted);
        assertFalse("screen started after an input", result.started);
    }

    private record Result(boolean started, int accepted) {}

    /**
     * Announces the join screen, takes {@code inputsBefore} inputs, starts the screen and takes {@link #INPUTS} more;
     * returns whether the screen started and how many of those later inputs the model took as sent. Every input
     * reports the velocity a held actor keeps: none from the start, or the first tick of the fall an input began.
     */
    private static Result stayInTheAir(int inputsBefore) {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.gamemode = GameMode.SURVIVAL;
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            player.x = player.lastX = START.x;
            player.y = player.lastY = START.y;
            player.z = player.lastZ = START.z;
            player.onGround = player.lastOnGround = false;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, START.x, START.y, START.z);
            var loading = player.bedrockState.loadingScreen;
            loading.startGame();
            long tick = 1;
            Vec3 velocity = inputsBefore == 0 ? Vec3.ZERO : FALL;
            for (int input = 0; input < inputsBefore; input++) {
                BedrockFrameProcessor.process(
                        player, frame(player, tick++, velocity), BedrockPredictionTrigger.OFFLINE_REPLAY);
            }
            boolean started = loading.accept(START_LOADING_SCREEN, null);
            int accepted = 0;
            for (int input = 0; input < INPUTS; input++) {
                double violations = player.checkManager.getListener(BedrockMovement.class).violations;
                var state = BedrockFrameProcessor.process(
                        player, frame(player, tick++, velocity), BedrockPredictionTrigger.OFFLINE_REPLAY);
                if (state != null
                        && Math.abs(state.physicalFeetPosition().y() - START.y) < 1.0E-3
                        && player.checkManager.getListener(BedrockMovement.class).violations == violations
                        && !player.getSetbackTeleportUtil().isPendingSetback()) accepted++;
            }
            if (started) assertTrue(loading.accept(END_LOADING_SCREEN, null));
            return new Result(started, accepted);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static BedrockAuthInputFrame frame(CultPlayer player, long tick, Vec3 velocity) {
        return BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(tick)
                .position(START)
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .reportedEndOfTickVelocity(velocity)
                .build();
    }
}
