package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementAttributeState;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.protocol.BedrockMovementCorrection;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.player.CultPlayer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * A timed update is recorded on its frame and marks it. Only some updates make the frames since run again, and
 * that replay starts at the oldest frame any update has marked since the last one.
 */
public final class BedrockReplayMarksTest {
    @Test
    public void aBoostStartWhileTheBoostRunsMarksItsFrameAndTheStopReplaysFromIt() {
        var history = BedrockActorHistoryTest.history(1, 10);
        var rewind = new BedrockMovementRewind(history);
        var player = player();

        // The boost starts: every frame after the start runs again.
        var before = history.frames();
        rewind.queue(5, true, new BedrockReplayEvent.Boost(1_000_000));
        var started = rewind.apply(player, commit(history.current()), (state, world) -> world);
        assertEquals(1, frame(history, 5).events().size());
        assertNotSame(before.get(5), frame(history, 6));
        assertFalse(history.marked());

        // A second start while it runs is recorded and marked, and nothing runs again.
        before = history.frames();
        rewind.queue(7, true, new BedrockReplayEvent.Boost(1_000_000));
        assertSame(started, rewind.apply(player, started, (state, world) -> world));
        assertEquals(1, frame(history, 7).events().size());
        assertSame(before.get(7), frame(history, 8));
        assertTrue(history.marked());

        // The stop replays from the frame the second start marked, not from its own.
        rewind.queue(9, true, new BedrockReplayEvent.Boost(0));
        rewind.apply(player, started, (state, world) -> world);
        assertNotSame(before.get(7), frame(history, 8));
        assertNotSame(before.get(9), frame(history, 10));
        assertFalse(history.marked());
    }

    @Test
    public void aSecondStartOnTheNewestFrameKeepsItsMarkThroughTheReplayOfTheFirst() {
        var history = BedrockActorHistoryTest.history(1, 10);
        var rewind = new BedrockMovementRewind(history);
        var player = player();

        // Both starts are taken together. The first replays the newest frame. The second is stamped on that frame,
        // so it marks the frame that follows it, which does not exist until that replay is over.
        rewind.queue(9, true, new BedrockReplayEvent.Boost(1_000_000));
        rewind.queue(10, true, new BedrockReplayEvent.Boost(1_000_000));
        var started = rewind.apply(player, commit(history.current()), (state, world) -> world);
        assertEquals(1, frame(history, 10).events().size());
        assertTrue(history.marked());

        // The stop replays from the frame after the second start, not only from its own.
        for (long tick = 11; tick <= 14; tick++)
            history.record(BedrockActorHistoryTest.forward(
                    history.current().getFirst(), tick, BedrockActorHistoryTest.ground(), new Vec3d(0, 0, 1)));
        var before = history.frames();
        rewind.queue(13, true, new BedrockReplayEvent.Boost(0));
        rewind.apply(player, started, (state, world) -> world);
        assertSame(before.get(8), frame(history, 9));
        assertNotSame(before.get(10), frame(history, 11));
        assertNotSame(before.get(13), frame(history, 14));
        assertFalse(history.marked());
    }

    @Test
    public void aStopWithNoBoostRunningOnlyMarks() {
        var history = BedrockActorHistoryTest.history(1, 6);
        var rewind = new BedrockMovementRewind(history);
        var current = commit(history.current());
        var before = history.frames();
        rewind.queue(3, true, new BedrockReplayEvent.Boost(0));
        assertSame(current, rewind.apply(player(), current, (state, world) -> world));
        assertSame(before.get(5), frame(history, 6));
        assertTrue(history.marked());
    }

    @Test
    public void aCorrectionToWhatItsFrameReportedIsNotTaken() {
        var history = BedrockActorHistoryTest.history(1, 6);
        var rewind = new BedrockMovementRewind(history);
        var current = commit(history.current());
        var reported = frame(history, 3);

        rewind.queue(3, true, correction(3, reported.observedPosition(), reported.observedVelocity()));
        assertSame(current, rewind.apply(null, current));
        assertTrue(frame(history, 3).events().isEmpty());
        assertFalse(history.marked());

        // One that moves the frame runs the frames since again.
        Vec3d moved = reported.observedPosition().add(new Vec3d(0.5, 0, 0));
        rewind.queue(3, true, correction(3, moved, reported.observedVelocity()));
        var published = rewind.apply(null, current);
        assertEquals(1, frame(history, 3).events().size());
        assertEquals(
                0.5,
                BedrockProfileState.previousState(published.carry())
                                .physicalFeetPosition()
                                .x()
                        - BedrockProfileState.previousState(current.carry())
                                .physicalFeetPosition()
                                .x(),
                1.0E-3);
    }

    @Test
    public void aTimedTeleportMovesTheLiveStateWithoutRunningTheFramesSinceAgain() {
        var history = BedrockActorHistoryTest.history(1, 6);
        var rewind = new BedrockMovementRewind(history);
        var before = history.frames();
        var target = new Vec3d(295, 82, -80);
        rewind.queue(3, true, new BedrockReplayEvent.Reposition(target, 0, 0, true, BedrockCoordinateFrame.IDENTITY));
        var published = rewind.apply(null, commit(history.current()));
        assertEquals(
                target, BedrockProfileState.previousState(published.carry()).physicalFeetPosition());
        assertSame(before.get(5), frame(history, 6));
        assertEquals(1, frame(history, 3).events().size());
        assertTrue(history.marked());
    }

    @Test
    public void anEffectReplaysOnlyWhenItChangesTheLevelTheActorHas() {
        var history = BedrockActorHistoryTest.history(1, 6);
        var rewind = new BedrockMovementRewind(history);
        var current = commit(history.current());
        var before = history.frames();

        // Removing a speed effect the actor does not have.
        rewind.queue(3, true, new BedrockReplayContextEvent(Map.of(), 1, 0, -1, null, null));
        assertSame(current, rewind.apply(null, current));
        assertSame(before.get(5), frame(history, 6));
        assertTrue(history.marked());

        rewind.queue(4, true, new BedrockReplayContextEvent(Map.of(), 1, 2, 600, null, null));
        rewind.apply(null, current);
        // The replay starts at the frame the first update marked.
        assertNotSame(before.get(3), frame(history, 4));
        assertFalse(history.marked());
    }

    @Test
    public void aGameTypeOnlyMarks() {
        var history = BedrockActorHistoryTest.history(1, 6);
        var rewind = new BedrockMovementRewind(history);
        var current = commit(history.current());
        var before = history.frames();
        rewind.queue(3, true, new BedrockReplayContextEvent(Map.of(), null, null, -1, 1, null));
        assertSame(current, rewind.apply(null, current));
        assertSame(before.get(5), frame(history, 6));
        assertTrue(history.marked());
    }

    @Test
    public void aTimedAttributeIsRecordedOnItsFrameAndALaterReplayRunsTheFramesSinceWithIt() {
        var history = BedrockActorHistoryTest.history(1, 8);
        var rewind = new BedrockMovementRewind(history);
        var value = new BedrockReplayContextEvent(
                Map.of(), null, null, -1, null, null, BedrockMovementAttributeState.serverValue(0.2F, false), true);
        float ranWith =
                frame(history, 5).end().getFirst().state().movementAttribute().current();
        rewind.queue(3, true, value);
        var published = rewind.apply(null, commit(history.current()));
        float live = BedrockProfileState.previousState(published.carry())
                .movementAttribute()
                .current();
        assertTrue(live != ranWith);
        assertEquals(1, frame(history, 3).events().size());
        assertTrue(frame(history, 8).events().isEmpty());
        // The frames since still hold the value they ran with.
        assertEquals(
                ranWith,
                frame(history, 5).end().getFirst().state().movementAttribute().current(),
                0);

        var anchor = frame(history, 6).end().getFirst().state();
        rewind.queue(6, true, new BedrockReplayEvent.Motion(anchor.velocity()));
        rewind.apply(null, published);
        assertEquals(
                live,
                frame(history, 5).end().getFirst().state().movementAttribute().current(),
                0);
    }

    @Test
    public void aMarkLastsUntilTheFrameAfterItLeavesTheHistory() {
        var history = BedrockActorHistoryTest.history(1, BedrockActorHistory.CAPACITY);
        history.mark(1);
        var entry = history.current().getFirst();
        var next = BedrockActorHistoryTest.forward(
                entry, BedrockActorHistory.CAPACITY + 1, BedrockActorHistoryTest.ground(), new Vec3d(0, 0, 1));
        history.record(next);
        // Frame 1 has left, frame 2 is the oldest: a replay still starts there.
        assertTrue(history.marked());
        var copy = history.copy();
        var expected = copy.current();
        assertEquals(expected, copy.replay((state, world) -> world, (frame, state, world) -> world));
        assertNotSame(history.frames().getFirst(), copy.frames().getFirst());

        history.record(BedrockActorHistoryTest.forward(
                next.end().getFirst(),
                BedrockActorHistory.CAPACITY + 2,
                BedrockActorHistoryTest.ground(),
                new Vec3d(0, 0, 1)));
        assertFalse(history.marked());
    }

    private static BedrockActorHistory.Frame frame(BedrockActorHistory history, long tick) {
        return history.frame(tick);
    }

    private static BedrockReplayEvent.Transform correction(long tick, Vec3d position, Vec3d velocity) {
        return new BedrockReplayEvent.Transform(new BedrockMovementCorrection(
                1,
                0,
                -1,
                3,
                tick,
                BedrockVectorAdapter.toJava(position),
                BedrockVectorAdapter.toJava(velocity),
                0,
                0,
                true,
                BedrockCoordinateFrame.IDENTITY,
                -1,
                null,
                false));
    }

    private static CultPlayer player() {
        var player = Mockito.mock(CultPlayer.class);
        player.bedrockState = new BedrockPlayerState(new UUID(0, 1));
        return player;
    }

    private static PredictionCommit commit(List<BedrockProfileState.Entry> entries) {
        return new PredictionCommit(
                new BedrockNextTickStates(entries), BedrockNextTickVelocityDerivation.profileStateVelocities(entries));
    }
}
