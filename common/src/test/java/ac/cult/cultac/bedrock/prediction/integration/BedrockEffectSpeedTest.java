package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementAttributeState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * A speed or slowness effect changes the player's movement speed as the player takes it, before an attribute value
 * that carries the change arrives.
 */
public final class BedrockEffectSpeedTest {
    private static final float EPSILON = 1.0E-6F;

    @Test
    public void anEffectChangesTheSpeedThroughItsOwnModifier() {
        var state = BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(0, 82, 0), Vec3d.ZERO, BedrockInputFrame.idle(0), BedrockCollisionFlags.ON_GROUND)
                .withMovementAttribute(BedrockMovementAttributeState.serverValue(0.1F, true));
        assertEquals(0.13F, state.movementAttribute().current(), EPSILON);

        // Slowness while sprinting.
        state = effect(2, 1).withEffectSpeed(state);
        assertEquals(0.1105F, state.movementAttribute().current(), EPSILON);
        // The same level again replaces the modifier instead of adding a second one.
        state = effect(2, 1).withEffectSpeed(state);
        assertEquals(0.1105F, state.movementAttribute().current(), EPSILON);

        // A value that carries the slowness in its default replaces the modifier.
        var carried = new BedrockMovementAttributeState(
                0.1105F, 0, 1024, 0, 1024, 0.085F, List.of(BedrockMovementAttributeState.SPRINT));
        state = state.withMovementAttribute(state.movementAttribute().replace(carried));
        state = state.withMovementAttribute(state.movementAttribute().removeSprint());
        assertEquals(0.085F, state.movementAttribute().current(), EPSILON);

        // Speed II on top, then both effects end: only the speed modifier is there to remove.
        state = effect(1, 2).withEffectSpeed(state);
        assertEquals(0.119F, state.movementAttribute().current(), EPSILON);
        state = effect(2, 0).withEffectSpeed(state);
        assertEquals(0.119F, state.movementAttribute().current(), EPSILON);
        state = effect(1, 0).withEffectSpeed(state);
        assertEquals(0.085F, state.movementAttribute().current(), EPSILON);
    }

    @Test
    public void theLiveStateTakesTheEffectAndTheFrameBeforeItDoesNot() {
        var history = BedrockActorHistoryTest.history(1, 6);
        var rewind = new BedrockMovementRewind(history);
        float before = history.current().getFirst().state().movementAttribute().current();

        rewind.queue(0, false, effect(2, 1));
        var published = rewind.apply(null, commit(history.current()));
        float slowed = BedrockProfileState.previousState(published.carry())
                .movementAttribute()
                .current();
        assertEquals(before * 0.85F, slowed, EPSILON);
        // The frame the effect arrived after ended at the old speed: a value stamped on it is still a change.
        assertEquals(
                before, history.current().getFirst().state().movementAttribute().current(), 0);

        // The next frame runs with the effect; the value the server then stamps on it changes nothing.
        var entry = BedrockProfileState.profileEntries(published.carry()).getFirst();
        history.record(BedrockActorHistoryTest.forward(entry, 7, BedrockActorHistoryTest.ground(), new Vec3d(0, 0, 1)));
        var current = commit(history.current());
        rewind.queue(
                7,
                true,
                new BedrockReplayContextEvent(
                        Map.of(),
                        null,
                        null,
                        -1,
                        null,
                        null,
                        new BedrockMovementAttributeState(slowed, 0, 1024, 0, 1024, slowed, List.of()),
                        true));
        assertSame(current, rewind.apply(null, current));
    }

    @Test
    public void aVehicleKeepsItsSpeed() {
        var history = BedrockActorHistoryTest.history(1, 2);
        var state = history.current().getFirst().state();
        assertSame(state, effect(8, 2).withEffectSpeed(state));
    }

    private static BedrockReplayContextEvent effect(int id, int level) {
        return new BedrockReplayContextEvent(Map.of(), id, level, level == 0 ? -1 : 600, null, null);
    }

    private static PredictionCommit commit(List<BedrockProfileState.Entry> entries) {
        return new PredictionCommit(
                new BedrockNextTickStates(entries), BedrockNextTickVelocityDerivation.profileStateVelocities(entries));
    }
}
