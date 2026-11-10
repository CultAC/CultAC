package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassAfterTravel;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.manager.player.CheckManager;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** TridentItem#releaseUsing reads Entity#wasTouchingWater, which the last move left, not the live world. */
class RiptideReleaseWaterTest {
    @Test
    void aReleaseAfterAWetMoveIsValidInADrainedWorld() {
        assertTrue(withPlayer(false, player -> canRelease(player, true)));
    }

    @Test
    void aReleaseAfterADryMoveIsDryInAFloodedWorld() {
        assertFalse(withPlayer(true, player -> canRelease(player, false)));
    }

    @Test
    void aWetMoveKeepsAReleaseCertainAfterTheWorldDrains() {
        assertTrue(withPlayer(false, player -> spins(player, true)));
    }

    @Test
    void aReleaseWithoutACommittedMoveProbesTheWorld() {
        assertTrue(withPlayer(true, player -> canRelease(player, null)));
        assertFalse(withPlayer(false, player -> canRelease(player, null)));
    }

    @Test
    void onlyAWetLastMoveLeavesWasTouchingWaterForTheRelease() {
        assertTrue(withPlayer(false, player -> spins(player, true)));
        assertFalse(withPlayer(true, player -> spins(player, false)));
        assertFalse(withPlayer(true, player -> spins(player, null)));
    }

    /** From 26.3 the block-entity pass runs between the travel and the report; its Entity#move refreshes fluids. */
    @Test
    void aPistonShoveIntoWaterAfterADryTravelLeavesTheReleaseWet() {
        assertTrue(withPlayer(
                false,
                player -> afterPass(
                        player, false, passAfterTravel(true), null, RiptideUtil::canUseRiptideAtCurrentState)));
        assertTrue(withPlayer(
                false,
                player -> afterPass(player, false, passAfterTravel(true), null, RiptideUtil::isCertainRiptideRelease)));
    }

    @Test
    void aPistonShoveThatStaysDryKeepsTheReleaseDryInAFloodedWorld() {
        assertFalse(withPlayer(
                true,
                player -> afterPass(
                        player, false, passAfterTravel(false), null, RiptideUtil::canUseRiptideAtCurrentState)));
    }

    /** Before 26.3 the runner replays the pass after the report; the release reads where that pass left it. */
    @Test
    void aReplayedShoveIntoWaterLeavesTheReleaseWet() {
        assertTrue(withPlayer(
                false,
                player -> afterPass(player, false, null, passState(true), RiptideUtil::isCertainRiptideRelease)));
        assertTrue(withPlayer(
                false,
                player -> afterPass(
                        player,
                        false,
                        passAfterTravel(false),
                        passState(true),
                        RiptideUtil::canUseRiptideAtCurrentState)));
    }

    private static boolean canRelease(CultPlayer player, Boolean touchingWater) {
        return afterMove(player, touchingWater, RiptideUtil::canUseRiptideAtCurrentState);
    }

    private static boolean spins(CultPlayer player, Boolean touchingWater) {
        return afterMove(player, touchingWater, RiptideUtil::isCertainRiptideRelease);
    }

    private static boolean afterMove(CultPlayer player, Boolean touchingWater, Predicate<CultPlayer> rule) {
        return afterPass(player, touchingWater, null, null, rule);
    }

    private static boolean afterPass(
            CultPlayer player,
            Boolean touchingWater,
            JavaPassAfterTravel passAfterTravel,
            JavaPassState replayed,
            Predicate<CultPlayer> rule) {
        PredictionResult last = mock(PredictionResult.class);
        when(last.getJavaCommittedMove())
                .thenReturn(
                        touchingWater == null
                                ? null
                                : new JavaFallDistance.Moved(0.0D, Vec3.ZERO, false, touchingWater));
        if (passAfterTravel != null) {
            ValidMovements valid = mock(ValidMovements.class);
            when(valid.getClosestTrace())
                    .thenReturn(new MovementTrace(
                            new PredVector(Vec3.ZERO), Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, passAfterTravel));
            when(last.getValidMovements()).thenReturn(valid);
        }
        when(last.getJavaReplayedPassState()).thenReturn(replayed);
        SimulationProcessor processor = mock(SimulationProcessor.class);
        when(processor.getLastPrediction()).thenReturn(last);
        CheckManager checkManager = mock(CheckManager.class);
        when(checkManager.getSimulationProcessor()).thenReturn(processor);
        CheckManager real = player.checkManager;
        player.checkManager = checkManager;
        try {
            return rule.test(player);
        } finally {
            player.checkManager = real;
        }
    }

    /** A dry travel end, and the pass that shoved the player from it. */
    private static JavaPassAfterTravel passAfterTravel(boolean wetAfterPass) {
        return new JavaPassAfterTravel(
                passState(false),
                Pose.STANDING,
                new JavaBlockEntityPass.Result(passState(wetAfterPass), true, false, false, false),
                false,
                Vec3.ZERO,
                false,
                false);
    }

    private static JavaPassState passState(boolean inWater) {
        return new JavaPassState(
                Vec3.ZERO,
                Vec3.ZERO,
                false,
                new MainSupportingBlockData(null, false),
                null,
                0.0D,
                new ClientPistonDeltas(0.0D, 0.0D, 0.0D, 0L),
                false,
                inWater,
                false,
                false);
    }

    private static boolean withPlayer(boolean waterInWorld, Predicate<CultPlayer> test) {
        OfflineCultTestBootstrap.installConfig();
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), "Riptide_Water"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        user.bind(mock(PlatformPlayer.class));
        try {
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            if (waterInWorld) {
                player.compensatedWorld.updateBlock(
                        0,
                        64,
                        0,
                        DataTables.defaults()
                                .registry()
                                .block("minecraft:water")
                                .defaultState());
            }
            player.x = 0.5D;
            player.y = 64.0D;
            player.z = 0.5D;
            return test.test(player);
        } finally {
            Object channel = player.user.getChannel();
            player.onRemove();
            if (channel instanceof EmbeddedChannel embedded) {
                embedded.runPendingTasks();
                embedded.runScheduledPendingTasks();
                embedded.finishAndReleaseAll();
            }
        }
    }
}
