package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState.Entry;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementAttributeState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Speed V and the value that carries it, stamped on the frame it arrived after, then speed swapped for slowness II
     * before the next value arrives. The stamped value was not the frame's own, so it replaces the live attribute in
     * full although the live state had already reached it with the speed modifier: the removal finds no speed
     * modifier, and the slowness applies to the stamped 0.2. A walking jump then moves 0.19797 + 0.14 * 0.98 forward,
     * as the client's 0.33517 did, not at 0.07.
     */
    @Test
    public void aStampedValueTheFrameDidNotHaveReplacesTheModifiersTheLiveStateTook() {
        var history = new BedrockActorHistory();
        var entry = new Entry(
                BedrockMovementState.fromPhysicalFeet(
                                new Vec3d(289, 82, -86),
                                new Vec3d(0, -0.0784F, 0),
                                BedrockInputFrame.idle(0),
                                BedrockCollisionFlags.ON_GROUND)
                        .withMovementAttribute(BedrockMovementAttributeState.serverValue(0.1F, false)),
                BedrockMobJumpComponentState.DEFAULT);
        for (long tick = 1; tick <= 6; tick++) {
            var frame = tick(entry, tick, false);
            history.record(frame);
            entry = frame.end().getFirst();
        }
        var rewind = new BedrockMovementRewind(history);

        rewind.queue(0, false, effect(1, 5));
        rewind.queue(6, true, value(0.2F));
        var published = rewind.apply(null, commit(history.current()));
        entry = BedrockProfileState.profileEntries(published.carry()).getFirst();
        assertEquals(0.2F, entry.state().movementAttribute().current(), EPSILON);
        for (long tick = 7; tick <= 10; tick++) {
            var frame = tick(entry, tick, false);
            history.record(frame);
            entry = frame.end().getFirst();
        }

        rewind.queue(0, false, effect(1, 0));
        rewind.queue(0, false, effect(2, 2));
        published = rewind.apply(null, commit(history.current()));
        entry = BedrockProfileState.profileEntries(published.carry()).getFirst();
        assertEquals(0.14F, entry.state().movementAttribute().current(), EPSILON);

        // The jump tick the client reported, from its landing velocity.
        var landed = new Entry(
                entry.state()
                        .withVelocityAndCollisionFlags(
                                new Vec3d(0, -0.0784F, 0.19796695F),
                                entry.state().collisionFlags()),
                entry.mobJumpComponent());
        var jump = tick(landed, 11, true).end().getFirst().state();
        double forward = jump.physicalFeetPosition().z()
                - landed.state().physicalFeetPosition().z();
        assertEquals(0.335167, forward, 0.001);
    }

    /**
     * While a stamped value recorded since the last replay is pending, a later one is taken in full even when it equals
     * its frame. Sprinting under slowness II, the slowness is swapped for speed V together with a stale value that
     * still carries the slowness (0.091, its frame's own): the client drops the speed modifier it just took and jumps
     * at 0.091, 0.26244 + 0.2 + 0.091 * 0.98 forward as its 0.55161 did, not at 0.182.
     */
    @Test
    public void aPendingStampedValueMakesTheNextOneReplaceTheLiveAttributeEvenAtItsFramesValue() {
        var history = new BedrockActorHistory();
        var entry = new Entry(
                BedrockMovementState.fromPhysicalFeet(
                                new Vec3d(289, 82, -86),
                                new Vec3d(0, -0.0784F, 0),
                                BedrockInputFrame.idle(0),
                                BedrockCollisionFlags.ON_GROUND)
                        .withMovementAttribute(BedrockMovementAttributeState.serverValue(0.2F, true))
                        .withSprinting(true),
                BedrockMobJumpComponentState.DEFAULT);
        for (long tick = 1; tick <= 6; tick++) {
            var frame = tick(entry, tick, false, true);
            history.record(frame);
            entry = frame.end().getFirst();
        }
        var rewind = new BedrockMovementRewind(history);
        assertEquals(0.26F, entry.state().movementAttribute().current(), EPSILON);

        // Speed for slowness, with the value that carries it: not its frame's 0.26, so it is recorded.
        rewind.queue(0, false, effect(1, 0));
        rewind.queue(0, false, effect(2, 2));
        rewind.queue(6, true, sprintingValue(0.07F));
        var published = rewind.apply(null, commit(history.current()));
        entry = BedrockProfileState.profileEntries(published.carry()).getFirst();
        assertEquals(0.091F, entry.state().movementAttribute().current(), EPSILON);
        for (long tick = 7; tick <= 10; tick++) {
            var frame = tick(entry, tick, false, true);
            history.record(frame);
            entry = frame.end().getFirst();
        }

        // Slowness for speed, with a value that still carries the slowness: its frame's own 0.091.
        rewind.queue(0, false, effect(2, 0));
        rewind.queue(0, false, effect(1, 5));
        rewind.queue(10, true, sprintingValue(0.07F));
        published = rewind.apply(null, commit(history.current()));
        entry = BedrockProfileState.profileEntries(published.carry()).getFirst();
        assertEquals(0.091F, entry.state().movementAttribute().current(), EPSILON);

        var landed = new Entry(
                entry.state()
                        .withVelocityAndCollisionFlags(
                                new Vec3d(0, -0.0784F, 0.2624262F),
                                entry.state().collisionFlags()),
                entry.mobJumpComponent());
        var jump = tick(landed, 11, true, true).end().getFirst().state();
        double forward = jump.physicalFeetPosition().z()
                - landed.state().physicalFeetPosition().z();
        assertEquals(0.5516062, forward, 0.001);
    }

    private static BedrockActorHistory.Frame tick(Entry previous, long tick, boolean jumping) {
        return tick(previous, tick, jumping, false);
    }

    private static BedrockActorHistory.Frame tick(Entry previous, long tick, boolean jumping, boolean sprinting) {
        var flags = new java.util.HashSet<>(Set.of("UP"));
        if (jumping) flags.addAll(Set.of("JUMPING", "JUMP_DOWN", "START_JUMPING", "WANT_UP"));
        if (sprinting) flags.addAll(Set.of("SPRINTING", "SPRINT_DOWN"));
        var input = new BedrockInputFrame(tick, 0, 0, jumping, false, sprinting, Set.copyOf(flags));
        var request = new BedrockSimulation.Input(
                previous.state(),
                input,
                input.intent(),
                BedrockActorHistoryTest.ground(),
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                previous.mobJumpComponent(),
                true,
                false,
                new Vec3d(0, 0, 1));
        var movement = BedrockForwardTick.simulate(request).getFirst();
        var state = movement.movementResult().predictedState();
        var entries = List.of(
                new Entry(BedrockForwardTick.finish(movement.movementResult(), state), movement.mobJumpComponent()));
        return new BedrockActorHistory.Frame(
                tick,
                request,
                entries,
                entries,
                null,
                Vec3d.ZERO,
                Vec3d.ZERO,
                state.physicalFeetPosition(),
                state.velocity(),
                List.of());
    }

    private static BedrockReplayAttributeEvent sprintingValue(float base) {
        return new BedrockReplayAttributeEvent(
                Map.of("minecraft:movement", BedrockMovementAttributeState.serverValue(base, true)), true);
    }

    private static BedrockReplayAttributeEvent value(float movement) {
        return new BedrockReplayAttributeEvent(
                Map.of(
                        "minecraft:movement",
                        new BedrockMovementAttributeState(movement, 0, 1024, 0, 1024, movement, List.of())),
                true);
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
