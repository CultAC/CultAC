package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.*;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockCollisionSweep;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.*;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BedrockMetadataReplayTest {
    private static final BedrockReplayEvent.Metadata CANCEL =
            new BedrockReplayEvent.Metadata(null, null, false, null, null, null, null);

    @Test public void boostReplayAppliesSavedCancellationBeforeLaterStartInsteadOfAtReceipt() {
        var history = history();
        var before = history.current().getFirst().state();
        history.metadata(2, CANCEL);
        assertFalse(history.current().getFirst().state().gliding());
        assertEquals(before.physicalFeetPosition(), history.current().getFirst().state().physicalFeetPosition());
        assertEquals(before.velocity(), history.current().getFirst().state().velocity());

        var expected = history.frames().getFirst().end().getFirst().state();
        for (long tick = 2; tick <= 5; tick++) {
            expected = forward(expected, tick, true).end().getFirst().state();
            if (tick == 2) expected = CANCEL.state(expected);
        }
        var actual = history.apply(1, new BedrockReplayEvent.Boost(100), (state, world) -> world)
                .getFirst().state();
        assertTrue(actual.gliding());
        assertEquals(expected.physicalFeetPosition(), actual.physicalFeetPosition());
        assertEquals(expected.velocity(), actual.velocity());
        assertEquals(expected.glidingRequest(), actual.glidingRequest());
        assertTrue(actual.physicalFeetPosition().subtract(before.physicalFeetPosition()).length() > 0.001);
    }

    @Test public void replayAtMetadataAnchorIncludesTheSavedMask() {
        var history = history();
        history.metadata(2, CANCEL);
        var expected = CANCEL.state(history.frames().get(1).beforeEvents().getFirst().state());
        for (long tick = 3; tick <= 5; tick++) expected = forward(expected, tick, true).end().getFirst().state();
        var actual = history.apply(2, new BedrockReplayEvent.Boost(100), (state, world) -> world)
                .getFirst().state();
        assertEquals(expected.physicalFeetPosition(), actual.physicalFeetPosition());
        assertEquals(expected.velocity(), actual.velocity());
        assertTrue(actual.gliding());
    }

    @Test public void dimensionDeliveryIsSeparateFromHistoricalFlagPlacement() {
        var history = history();
        history.metadata(2, new BedrockReplayEvent.Metadata(null, 1.8F, false, null, null, null, null));
        var historical = (BedrockReplayEvent.Metadata) history.frames().get(1).events().getFirst().value();
        assertEquals(CANCEL, historical);
        assertTrue(history.frames().getLast().events().isEmpty());
        assertEquals(1.8F, history.current().getFirst().state().playerDimensions().height(), 0);
    }

    @Test public void boostRewindRestoresSavedBoxWithoutReplayingLaterHeightDelivery() {
        var history = history(true);
        var current = new ac.cult.cultac.checks.impl.prediction.PredictionCommit(
                new BedrockNextTickStates(history.current()), Set.of());
        var player = org.mockito.Mockito.mock(ac.cult.cultac.player.CultPlayer.class);
        player.bedrockState = new ac.cult.cultac.bedrock.player.BedrockPlayerState(new java.util.UUID(0, 1));
        var rewind = new BedrockMovementRewind(history);

        rewind.metadata(0, new BedrockReplayEvent.Metadata(null, 0.6F, null, null, null, null, null));
        player.bedrockState.applyAcknowledgedBoundingBoxMetadata(0.6F, 0.6F);
        assertEquals(0.6F, history.current().getFirst().state().playerDimensions().height(), 0);
        rewind.queue(2, true, new BedrockReplayEvent.Boost(100));
        var published = rewind.apply(player, current, (state, world) -> world);
        var restored = BedrockProfileState.previousState(published.carry());
        assertTrue(restored.gliding());
        assertEquals(1.8F, restored.playerDimensions().height(), 0);
        assertEquals(0.6F, restored.acknowledgedPlayerDimensions().height(), 0);
        assertSame(restored, player.bedrockState.applyConfirmedBoundingBoxSize(restored, restored.inputFrame()));

        var leaves = PlacedBlockCollision.manual(new BlockPosition(281, 71, -164),
                "minecraft:dark_oak_leaves", "minecraft:dark_oak_leaves",
                List.of(new WorldCollisionBox(281, 71, -164, 283, 72, -162)));
        var collision = BedrockCollisionSweep.sweep(
                new Vec3d(282.033203125, 68.79495239257812, -163.033447265625),
                new Vec3d(-0.5847306847572327, 0.46674373745918274, 0.404387503862381),
                new BlockCollisionWorld(List.of(leaves)).collisions(), restored.playerDimensions());
        assertTrue(collision.position().subtract(new Vec3d(
                281.448486328125, 69.19999694824219, -162.62905883789062)).length() <= 0.001);

        player.bedrockState.applyAcknowledgedBoundingBoxMetadata(0.7F, null);
        var resized = player.bedrockState.applyConfirmedBoundingBoxSize(restored, restored.inputFrame());
        assertEquals(0.7F, resized.playerDimensions().width(), 0);
        assertEquals(0.6F, resized.playerDimensions().height(), 0);
    }

    @Test public void rewindAfterResizeKeepsTheShortBoxSavedInItsAnchor() {
        var history = history(true);
        history.metadata(0, new BedrockReplayEvent.Metadata(null, 0.6F, null, null, null, null, null));
        history.record(forward(history.current().getFirst().state(), 6, false));
        history.record(forward(history.current().getFirst().state(), 7, false));
        var restored = history.apply(6, new BedrockReplayEvent.Boost(100), (state, world) -> world)
                .getFirst().state();
        assertTrue(restored.gliding());
        assertEquals(0.6F, restored.playerDimensions().height(), 0);
    }

    @Test public void confirmationDoesNotCreateAnEventAndCopiedHistoryRetainsMasks() {
        var history = history();
        history.metadata(2, new BedrockReplayEvent.Metadata(null, null, true, null, null, null, null));
        assertTrue(history.frames().stream().allMatch(frame -> frame.events().isEmpty()));
        history.metadata(2, CANCEL);
        var copy = history.copy();
        copy.apply(1, new BedrockReplayEvent.Boost(100), (state, world) -> world);
        assertTrue(copy.current().getFirst().state().gliding());
        assertFalse(history.current().getFirst().state().gliding());
    }

    @Test public void matchingUpperFlagWordDefersAnEarlierReplayUntilMovementCorrection() {
        var history = pendingConfirmation(2);
        var copy = history.copy();
        assertEquals(0.6F, history.current().getFirst().state().playerDimensions().height(), 0);
        assertTrue(history.frames().stream().allMatch(frame -> frame.events().isEmpty()));
        var restored = copy.apply(6, new BedrockReplayEvent.Boost(100), (state, world) -> world)
                .getFirst().state();
        assertEquals(1.8F, restored.playerDimensions().height(), 0);
        assertEquals(0.6F, history.current().getFirst().state().playerDimensions().height(), 0);

        var lowerOnly = pendingConfirmation(1);
        assertEquals(0.6F, lowerOnly.apply(6, new BedrockReplayEvent.Boost(100), (state, world) -> world)
                .getFirst().state().playerDimensions().height(), 0);

        copy.metadata(0, new BedrockReplayEvent.Metadata(null, 0.6F, null, null, null, null, null));
        for (long tick = 8; tick <= 9; tick++) copy.record(forward(copy.current().getFirst().state(), tick, false));
        assertEquals(0.6F, copy.apply(8, new BedrockReplayEvent.Boost(100), (state, world) -> world)
                .getFirst().state().playerDimensions().height(), 0);
    }

    @Test public void pendingMetadataExpiresWithItsFollowingFrame() {
        var history = pendingConfirmation(2);
        for (long tick = 8; tick <= 42; tick++) history.record(forward(history.current().getFirst().state(), tick, false));
        assertEquals(3, history.oldestTick());
        var retained = history.copy();
        assertEquals(1.8F, retained.apply(41, new BedrockReplayEvent.Boost(100), (state, world) -> world)
                .getFirst().state().playerDimensions().height(), 0);
        history.record(forward(history.current().getFirst().state(), 43, false));
        assertEquals(4, history.oldestTick());
        assertEquals(0.6F, history.apply(42, new BedrockReplayEvent.Boost(100), (state, world) -> world)
                .getFirst().state().playerDimensions().height(), 0);
    }

    @Test public void flag125RestoredBoxClipsTheRecordedLeavesAndClearsFutureZVelocity() {
        var history = pendingConfirmation(2);
        var dimensions = history.apply(6, new BedrockReplayEvent.Boost(100), (state, world) -> world)
                .getFirst().state().playerDimensions();
        var obstacles = List.of(
                new WorldCollisionBox(519, 70, -21, 520, 71, -20),
                new WorldCollisionBox(520, 70, -22, 521, 71, -21),
                new WorldCollisionBox(520, 69, -21, 521, 70, -20),
                new WorldCollisionBox(520, 70, -21, 521, 71, -20),
                new WorldCollisionBox(521, 70, -22, 522, 71, -21),
                new WorldCollisionBox(521, 69, -21, 522, 70, -20),
                new WorldCollisionBox(521, 70, -21, 522, 71, -20))
                .stream().map(BlockCollision::raw).toList();
        var start = new Vec3d(519.9891967773438, 68.21668243408203, -22.787073135375977);
        var requested = new Vec3d(0.7260316014289856, -0.11001903563737869, 1.4890620708465576);
        var observed = new Vec3d(520.7152099609375, 68.1066665649414, -21.30000114440918);
        var result = BedrockCollisionSweep.sweep(start, requested, obstacles, dimensions,
                ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame.IDENTITY, 0.01F);
        assertTrue(result.position().subtract(observed).length() <= 0.001);
        assertFalse(result.xCollision());
        assertFalse(result.yCollision());
        assertTrue(result.zCollision());
        var shortBox = BedrockCollisionSweep.sweep(start, requested, obstacles,
                new PlayerDimensionsState(0.6F, 0.6F),
                ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame.IDENTITY, 0.01F);
        assertFalse(shortBox.zCollision());
        assertTrue(shortBox.position().subtract(observed).length() > 0.001);
    }

    @Test public void replayRestoresLiveFlagDeltaAtReceiptAfterHistoricalCorrections() {
        var history = history(true);
        history.metadata(2, CANCEL);
        var sixth = forward(history.current().getFirst().state(), 6, false);
        history.record(sixth);
        assertEquals(CANCEL, history.frames().getLast().receivedFlags());
        history.record(forward(history.current().getFirst().state(), 7, false));
        var copy = history.copy();
        var restored = copy.apply(1, new BedrockReplayEvent.Boost(100), (state, world) -> world)
                .getFirst().state();
        assertFalse(restored.gliding());
        assertTrue(restored.glidingRequest());
        assertNull(history.frames().getLast().receivedFlags());
        assertEquals(CANCEL, copy.frames().get(5).receivedFlags());
    }

    @Test public void receiptTrackerRecordsNetChangesAndNeverSizeOrMatchingFlags() {
        var history = history(true);
        history.metadata(2, CANCEL);
        history.metadata(1, new BedrockReplayEvent.Metadata(null, 0.6F, true, null, null, null, null));
        history.record(forward(history.current().getFirst().state(), 6, false));
        assertNull(history.frames().getLast().receivedFlags());
        assertEquals(0.6F, history.current().getFirst().state().playerDimensions().height(), 0);

        history.metadata(1, new BedrockReplayEvent.Metadata(null, null, true, null, null, null, null));
        history.record(forward(history.current().getFirst().state(), 7, false));
        assertNull(history.frames().getLast().receivedFlags());
    }

    private static BedrockActorHistory pendingConfirmation(int flagWords) {
        var history = history(true);
        var before = history.current().getFirst().state();
        history.metadata(2, new BedrockReplayEvent.Metadata(null, null, true, null, null, null, null), flagWords);
        assertEquals(before.physicalFeetPosition(), history.current().getFirst().state().physicalFeetPosition());
        history.metadata(0, new BedrockReplayEvent.Metadata(null, 0.6F, null, null, null, null, null));
        for (long tick = 6; tick <= 7; tick++) history.record(forward(history.current().getFirst().state(), tick, false));
        return history;
    }

    private static BedrockActorHistory history() {
        return history(false);
    }

    private static BedrockActorHistory history(boolean explicitStandingBox) {
        var history = new BedrockActorHistory();
        var state = BedrockMovementState.fromPhysicalFeet(new Vec3d(0, 80, 0), new Vec3d(0.1, -0.1, 0),
                BedrockInputFrame.idle(0), BedrockCollisionFlags.AIR);
        if (explicitStandingBox) state = state.withPlayerDimensions(new PlayerDimensionsState(0.6F, 1.8F), true);
        for (long tick = 1; tick <= 5; tick++) {
            var frame = forward(state, tick, false);
            history.record(frame);
            state = frame.end().getFirst().state();
        }
        return history;
    }

    private static BedrockActorHistory.Frame forward(BedrockMovementState state, long tick, boolean boost) {
        var frame = new BedrockInputFrame(tick, -75, -4, false, false, false,
                tick == 2 || tick == 4 ? Set.of("START_GLIDING") : Set.of());
        var world = BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE, AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of())),
                new EquipmentState(0, 0, 0, false, true), EntityContactState.NONE,
                new MovementModifierState(true, false, false, false, 0.05, false, false,
                        false, false, 0.35, 0), PlayerDimensionsState.DEFAULT));
        var input = new BedrockSimulation.Input(state, frame, frame.intent(), world, false,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP, BedrockMobJumpComponentState.DEFAULT,
                true, false, Vec3d.ZERO, boost);
        var candidate = BedrockForwardTick.simulate(input).getFirst();
        var result = candidate.movementResult();
        var entries = BedrockForwardTick.finish(result, result.predictedState()).stream()
                .map(next -> new BedrockProfileState.Entry(next, candidate.mobJumpComponent())).toList();
        return new BedrockActorHistory.Frame(tick, input, entries, entries, null, Vec3d.ZERO, Vec3d.ZERO,
                result.predictedPosition(), result.predictedVelocity(), List.of());
    }
}
