package ac.cult.cultac.bedrock.prediction.integration;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BedrockMetadataHistoryTest {
    private static BedrockReplayEvent.Metadata glide(boolean value) {
        return new BedrockReplayEvent.Metadata(null, null, value, null, null, null, null);
    }

    private static BedrockActorHistory history(boolean first, boolean last) {
        var source = BedrockActorHistoryTest.history(1, 4);
        var history = new BedrockActorHistory();
        for (var frame : source.frames()) {
            var entries = frame.end().stream().map(entry -> entry.withState(
                    entry.state().withGliding(frame.tick() == 1 ? first : last))).toList();
            history.record(frame.withEnd(entries, entries, List.of()));
        }
        return history;
    }

    @Test public void oldCancellationUpdatesLivePoseWithoutReplayingMovement() {
        var history = history(true, true);
        var before = history.current().getFirst().state();
        var earlier = history.frames().subList(0, 3);
        assertEquals(Boolean.FALSE, history.metadata(1, glide(false)).gliding());
        var after = history.current().getFirst().state();
        assertFalse(after.gliding());
        assertEquals(before.velocity(), after.velocity());
        assertEquals(before.physicalFeetPosition(), after.physicalFeetPosition());
        assertEquals(before.simulationTick(), after.simulationTick());
        for (int i = 0; i < earlier.size(); i++) {
            assertEquals(earlier.get(i).end(), history.frames().get(i).end());
            assertEquals(earlier.get(i).beforeEvents(), history.frames().get(i).beforeEvents());
        }
        assertEquals(glide(false), history.frames().getFirst().events().getFirst().value());
        assertTrue(history.frames().getLast().events().isEmpty());
    }

    @Test public void historicalConfirmationDoesNotReviveNewerCancelledGlide() {
        var history = history(true, false);
        assertNull(history.metadata(1, glide(true)).gliding());
        assertFalse(history.current().getFirst().state().gliding());
    }

    @Test public void partialDimensionsAndFlagsPreserveUnspecifiedFields() {
        var history = history(true, true);
        var before = history.current().getFirst().state();
        var update = new BedrockReplayEvent.Metadata(null, 0.6F, null, null, null, null, null);
        history.metadata(0, update);
        var after = history.current().getFirst().state();
        assertTrue(after.gliding());
        assertEquals(before.playerDimensions().width(), after.playerDimensions().width(), 0);
        assertEquals(0.6F, after.playerDimensions().height(), 0);
        assertEquals(before.velocity(), after.velocity());
        assertEquals(before.physicalFeetPosition(), after.physicalFeetPosition());
    }

    @Test public void expiredConfirmationsDoNotCancelNewerGliding() {
        var history = new BedrockActorHistory();
        for (var frame : BedrockActorHistoryTest.history(100, 103).frames()) {
            var entries = frame.end().stream().map(entry -> entry.withState(
                    entry.state().withGliding(frame.tick() != 100))).toList();
            history.record(frame.withEnd(entries, entries, List.of()));
        }
        var before = history.current().getFirst().state();
        assertNull(history.metadata(1, glide(false)).gliding());
        assertEquals(Boolean.TRUE, history.metadata(7, glide(true)).gliding());
        assertNull(history.metadata(7, glide(false)).gliding());
        var after = history.current().getFirst().state();
        assertTrue(after.gliding());
        assertEquals(before.velocity(), after.velocity());
        assertEquals(before.physicalFeetPosition(), after.physicalFeetPosition());
        assertEquals(Boolean.FALSE, history.metadata(0, glide(false)).gliding());
        assertFalse(history.current().getFirst().state().gliding());
    }

    @Test public void expiredCorrectionStillCancelsWhenOldestFrameWasGliding() {
        var history = new BedrockActorHistory();
        for (var frame : BedrockActorHistoryTest.history(100, 103).frames()) {
            var entries = frame.end().stream().map(entry -> entry.withState(entry.state().withGliding(true))).toList();
            history.record(frame.withEnd(entries, entries, List.of()));
        }
        assertEquals(Boolean.FALSE, history.metadata(1, glide(false)).gliding());
        assertFalse(history.current().getFirst().state().gliding());
    }

    @Test public void missingHistoryFallsBackToLiveApplicationAndPreservesWireOrder() {
        var empty = new BedrockActorHistory();
        assertEquals(glide(false), empty.metadata(100, glide(false)));
        var history = history(false, true);
        history.metadata(100, glide(false));
        history.metadata(100, glide(true));
        assertTrue(history.current().getFirst().state().gliding());
        assertEquals(2, history.frames().getLast().events().size());
    }
}
