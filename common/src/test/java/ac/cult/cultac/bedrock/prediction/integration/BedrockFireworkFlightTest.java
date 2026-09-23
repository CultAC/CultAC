package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.*;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockAerialMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.*;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BedrockFireworkFlightTest {
    private static final float[][] CAPTURE = {
        {4.722473F, -139.05652F, 2797.5234F, 69.87374F, 14.529543F, 1.0953405F, -0.13802394F, -1.2657098F},
        {11.73848F, -135.93831F, 2798.6467F, 69.63324F, 13.309202F, 1.12332F, -0.24050505F, -1.2203401F},
        {14.466904F, -138.5368F, 2799.7483F, 69.307846F, 12.085186F, 1.1016216F, -0.32539368F, -1.2240167F},
        {14.466904F, -138.66673F, 2800.8406F, 68.944756F, 10.855929F, 1.0923412F, -0.36308905F, -1.2292569F},
        {14.466904F, -138.66673F, 2801.9297F, 68.564926F, 9.62324F, 1.0891721F, -0.37982792F, -1.232689F},
        {14.466904F, -138.66672F, 2803.0178F, 68.177666F, 8.38848F, 1.0882373F, -0.3872609F, -1.2347604F},
        {14.466904F, -138.66672F, 2804.106F, 67.7871F, 7.1525292F, 1.0880564F, -0.3905616F, -1.2359511F},
        {14.466904F, -138.66672F, 2805.194F, 67.39507F, 5.9159155F, 1.0880922F, -0.3920273F, -1.2366139F},
        {14.596817F, -144.64333F, 2806.2073F, 67.00049F, 4.620245F, 1.0131539F, -0.3945859F, -1.2956703F},
    };

    @Test
    public void capturedStopReceiptTicksRequireBoostInAllThreeNewFlags() {
        float[][] observations = {
            {392, -19.703552F, 6.0764465F, 0.57539713F, -0.17726061F, 1.5721464F,
                0.56934404F, -0.17555954F, 1.5743872F},
            {454, 171.78302F, 20.238113F, -0.2297965F, -0.54286855F, -1.5911839F,
                -0.22979674F, -0.54286844F, -1.5911837F},
            {473, 166.06635F, 0.61943054F, -0.39745316F, -0.035150975F, -1.6299027F,
                -0.40031302F, -0.03326929F, -1.6257886F},
        };
        for (float[] row : observations) {
            var frame = new BedrockInputFrame((long) row[0], row[1], row[2], false, false, false);
            var previous = new Vec3d(row[3], row[4], row[5]);
            var observed = new Vec3d(row[6], row[7], row[8]);
            var boosted = BedrockAerialMovement.glideVelocity(previous, frame, true);
            var unboosted = BedrockAerialMovement.glideVelocity(previous, frame, false);
            assertTrue("boosted tick " + (long) row[0], boosted.subtract(observed).length() <= 0.001);
            assertTrue("unboosted tick " + (long) row[0], unboosted.subtract(observed).length() > 0.001);
        }
    }

    @Test
    public void capturedFlightCarriesSimulatedVelocityWithoutObservedReseeding() {
        var seed = CAPTURE[0];
        var state = BedrockMovementState.fromPhysicalFeet(position(seed), velocity(seed),
                BedrockInputFrame.idle(1408), BedrockCollisionFlags.AIR).withGliding(true);
        var world = air();
        var jump = BedrockMobJumpComponentState.DEFAULT;
        for (int i = 1; i < CAPTURE.length; i++) {
            var observed = CAPTURE[i];
            var frame = new BedrockInputFrame(1408 + i, observed[1], observed[0], false, false, false);
            var input = new BedrockSimulation.Input(state, frame, frame.intent(), world, true,
                    BedrockSimulation.DEFAULT_MAX_AUTO_STEP, jump, true, false, Vec3d.ZERO, true);
            var candidate = BedrockForwardTick.simulate(input).getFirst();
            var result = candidate.movementResult();
            state = BedrockForwardTick.finish(result, result.predictedState()).getFirst();
            jump = candidate.mobJumpComponent();
            assertTrue("position tick " + frame.clientTick() + ": " + state.physicalFeetPosition(),
                    state.physicalFeetPosition().subtract(position(observed)).length() <= 0.001);
            assertTrue("velocity tick " + frame.clientTick(),
                    state.velocity().subtract(velocity(observed)).length() <= 0.001);
        }
    }

    @Test
    public void recordedStopRewindsWithOneFinalBoostAtTheAnchorAndAtReceipt() {
        var seed = CAPTURE[0];
        var state = BedrockMovementState.fromPhysicalFeet(position(seed), velocity(seed),
                BedrockInputFrame.idle(1408), BedrockCollisionFlags.AIR).withGliding(true);
        var entry = new BedrockProfileState.Entry(state, BedrockMobJumpComponentState.DEFAULT);
        var history = new BedrockActorHistory();
        var input = request(entry, 1408, seed, true);
        history.record(new BedrockActorHistory.Frame(1408, input, List.of(entry), List.of(entry),
                null, Vec3d.ZERO, Vec3d.ZERO, position(seed), velocity(seed), List.of()));
        for (int i = 1; i < CAPTURE.length; i++) {
            var frame = forward(entry, 1408 + i, CAPTURE[i], true);
            history.record(frame);
            entry = frame.end().getFirst();
        }
        history.apply(1408, new BedrockReplayEvent.Boost(0), (previous, world) -> world);
        float[] receipt = {14.726746F, -155.81693F, 2807.3784F, 67.76475F, 3.2064514F,
                0.8668935F, -0.3161092F, -1.3923281F};
        var corrected = forward(history.current().getFirst(), 1417, receipt, true);
        assertCaptured(corrected.end().getFirst().state(), receipt);
        float[] following = {13.167664F, -163.61246F, 2808.2048F, 67.46383F, 1.7826157F,
                0.826295F, -0.30091926F, -1.4238358F};
        assertCaptured(forward(corrected.end().getFirst(), 1418, following, false)
                .end().getFirst().state(), following);
    }

    @Test
    public void acknowledgedStopPreservesReceiptTickAfterHistoricalReplay() {
        float[][] captured = {
            {6.3363037F, -20.35318F, 1953.7145F, 122.664246F, 19.238552F,
                0.5888684F, -0.18111311F, 1.5667931F},
            {6.0764465F, -19.703552F, 1954.2899F, 122.486984F, 20.8107F,
                0.57539713F, -0.17726061F, 1.5721464F},
            {6.0764465F, -19.703552F, 1954.8593F, 122.311424F, 22.385086F,
                0.56934404F, -0.17555954F, 1.5743872F},
            {6.0764465F, -19.573639F, 1955.4279F, 122.13813F, 23.960316F,
                0.5686164F, -0.17329171F, 1.5752289F},
        };
        var seed = captured[0];
        var state = BedrockMovementState.fromPhysicalFeet(position(seed), velocity(seed),
                BedrockInputFrame.idle(390), BedrockCollisionFlags.AIR).withGliding(true);
        var entry = new BedrockProfileState.Entry(state, BedrockMobJumpComponentState.DEFAULT);
        var history = new BedrockActorHistory();
        history.record(new BedrockActorHistory.Frame(390, request(entry, 390, seed, true),
                List.of(entry), List.of(entry), null, Vec3d.ZERO, Vec3d.ZERO,
                position(seed), velocity(seed), List.of()));
        var beforeReceipt = forward(entry, 391, captured[1], true);
        history.record(beforeReceipt);

        var player = org.mockito.Mockito.mock(ac.cult.cultac.player.CultPlayer.class);
        player.bedrockState = new ac.cult.cultac.bedrock.player.BedrockPlayerState(new java.util.UUID(0, 1));
        player.bedrockState.movementEffects.setGlideBoost(0, 391);
        var rewind = new BedrockMovementRewind(history);
        rewind.queue(390, true, new BedrockReplayEvent.Boost(0));
        var current = new ac.cult.cultac.checks.impl.prediction.PredictionCommit(
                new BedrockNextTickStates(history.current()),
                BedrockNextTickVelocityDerivation.profileStateVelocities(history.current()));
        var commit = rewind.apply(player, current, (previous, world) -> world);
        var previous = BedrockProfileState.profileEntries(commit.carry()).getFirst();
        assertCaptured(previous.state(), captured[1]);

        var receipt = ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame
                .builder(new java.util.UUID(0, 1)).clientTick(392).build();
        assertTrue(player.bedrockState.movementEffects.glideBoost(receipt, true));
        var finalFrame = forward(previous, 392, captured[2], true);
        assertCaptured(finalFrame.end().getFirst().state(), captured[2]);
        var following = ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame
                .builder(new java.util.UUID(0, 1)).clientTick(393).build();
        assertFalse(player.bedrockState.movementEffects.glideBoost(following, true));
        assertCaptured(forward(finalFrame.end().getFirst(), 393, captured[3], false)
                .end().getFirst().state(), captured[3]);
    }

    @Test
    public void capturedZeroTickBoostReplaysEarlierFlightInsteadOfOnlyBoostingReceipt() {
        float[][] captured = {
            {-11.000992F, -86.41185F, 2869.7666F, 65.79676F, -11.961619F, 0.25867042F, -0.15233518F, 0.023279998F},
            {-12.690002F, -87.581154F, 2870.0356F, 65.64853F, -11.939252F, 0.2689789F, -0.14822827F, 0.022367684F},
            {-14.379013F, -88.49062F, 2870.314F, 65.50425F, -11.918305F, 0.2783533F, -0.14427526F, 0.020946281F},
            {-14.898712F, -88.75049F, 2870.601F, 65.36377F, -11.898792F, 0.28707978F, -0.14047953F, 0.019513493F},
            {-15.0286255F, -88.8804F, 2870.8962F, 65.226944F, -11.880637F, 0.2952813F, -0.13682918F, 0.018155234F},
            {-15.158554F, -89.01034F, 2871.1992F, 65.09362F, -11.863772F, 0.30297953F, -0.13331974F, 0.01686466F},
            {-15.158554F, -89.01034F, 2871.5095F, 64.96367F, -11.848051F, 0.31022972F, -0.12995133F, 0.015721507F},
            {-16.327866F, -89.78986F, 2871.8262F, 64.83698F, -11.833897F, 0.31674817F, -0.12668699F, 0.014154792F},
            {-16.977478F, -90.569405F, 2872.149F, 64.71343F, -11.821684F, 0.32268333F, -0.12354863F, 0.012212357F},
            {-17.107407F, -90.829254F, 2872.477F, 64.592896F, -11.811388F, 0.32819572F, -0.1205387F, 0.010296077F},
            {-17.237335F, -91.21903F, 2872.8103F, 64.47524F, -11.803081F, 0.33330438F, -0.11765506F, 0.00830746F},
            {-17.497177F, -92.518265F, 2873.1482F, 64.36035F, -11.79748F, 0.3379653F, -0.1148924F, 0.0056008543F},
            {-17.497177F, -93.94743F, 2887.556F, 70.791275F, -11.895823F, 1.5709355F, 0.52106655F, -0.08188219F},
        };
        var seed = captured[0];
        var state = BedrockMovementState.fromPhysicalFeet(position(seed), velocity(seed),
                BedrockInputFrame.idle(1041), BedrockCollisionFlags.AIR).withGliding(true);
        var entry = new BedrockProfileState.Entry(state, BedrockMobJumpComponentState.DEFAULT);
        var history = new BedrockActorHistory();
        history.record(new BedrockActorHistory.Frame(1041, request(entry, 1041, seed, false),
                List.of(entry), List.of(entry), null, Vec3d.ZERO, Vec3d.ZERO,
                position(seed), velocity(seed), List.of()));
        for (int i = 1; i < captured.length - 1; i++) {
            var frame = forward(entry, 1041 + i, captured[i], false);
            history.record(frame);
            entry = frame.end().getFirst();
        }
        var player = org.mockito.Mockito.mock(ac.cult.cultac.player.CultPlayer.class);
        player.bedrockState = new ac.cult.cultac.bedrock.player.BedrockPlayerState(new java.util.UUID(0, 1));
        var rewind = new BedrockMovementRewind(history);
        rewind.queue(0, true, new BedrockReplayEvent.Boost(1_000_000));
        var current = new ac.cult.cultac.checks.impl.prediction.PredictionCommit(
                new BedrockNextTickStates(history.current()),
                BedrockNextTickVelocityDerivation.profileStateVelocities(history.current()));
        var commit = rewind.apply(player, current, (previous, world) -> world);
        var previous = BedrockProfileState.profileEntries(commit.carry()).getFirst();
        var received = ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame
                .builder(new java.util.UUID(0, 1)).clientTick(1053).build();
        var result = forward(previous, 1053, captured[captured.length - 1],
                player.bedrockState.movementEffects.glideBoost(received, true));
        assertCaptured(result.end().getFirst().state(), captured[captured.length - 1]);
        assertEquals(1, history.frames().getFirst().events().size());
    }

    private static void assertCaptured(BedrockMovementState state, float[] observed) {
        double positionError = state.physicalFeetPosition().subtract(position(observed)).length();
        double velocityError = state.velocity().subtract(velocity(observed)).length();
        assertTrue("position residual " + positionError, positionError <= 0.001);
        assertTrue("velocity residual " + velocityError, velocityError <= 0.001);
    }

    private static BedrockSimulation.Input request(BedrockProfileState.Entry entry, long tick,
            float[] observed, boolean boost) {
        var frame = new BedrockInputFrame(tick, observed[1], observed[0], false, false, false);
        return new BedrockSimulation.Input(entry.state(), frame, frame.intent(), air(), true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP, entry.mobJumpComponent(), true, false, Vec3d.ZERO, boost);
    }

    private static BedrockActorHistory.Frame forward(BedrockProfileState.Entry entry, long tick,
            float[] observed, boolean boost) {
        var input = request(entry, tick, observed, boost);
        var candidate = BedrockForwardTick.simulate(input).getFirst();
        var result = candidate.movementResult();
        var entries = BedrockForwardTick.finish(result, result.predictedState()).stream()
                .map(state -> new BedrockProfileState.Entry(state, candidate.mobJumpComponent())).toList();
        return new BedrockActorHistory.Frame(tick, input, entries, entries, null, Vec3d.ZERO, Vec3d.ZERO,
                position(observed), velocity(observed), List.of());
    }

    private static BedrockWorldSnapshot air() {
        return BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE, AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of())),
                new EquipmentState(0, 0, 0, false, true), EntityContactState.NONE,
                new MovementModifierState(true, false, false, false, 0.05, false, false,
                        false, false, 0.35, 0), PlayerDimensionsState.DEFAULT));
    }

    private static Vec3d position(float[] row) {
        return new Vec3d(row[2], row[3] - 1.62F, row[4]);
    }

    private static Vec3d velocity(float[] row) {
        return new Vec3d(row[5], row[6], row[7]);
    }
}
