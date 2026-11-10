package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.*;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockHorseState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementAttributeState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class BedrockActorAttributesTest {
    @Test
    public void effectClearMetadataCannotRestoreOldHorseSpeed() {
        for (Boolean standing : new Boolean[] {null, false, true}) {
            var history = new BedrockActorHistory();
            history.record(BedrockActorHistoryTest.forward(
                    entry(attributes(0.35245588F, false).state(horse())),
                    1,
                    BedrockActorHistoryTest.ground(),
                    Vec3d.ZERO));
            var rewind = new BedrockMovementRewind(history);
            // Capture 1789933585258: effect clear, visible-effect metadata, Java attribute echo.
            rewind.queue(0, false, attributes(0.2961814F, false));
            rewind.queue(0, false, new BedrockReplayEvent.HorseMetadata(standing, null, null));
            rewind.queue(0, false, attributes(0.2961814F, false));
            var result = rewind.apply(null, commit(history.current()));
            var state = BedrockProfileState.previousState(result.carry());
            assertEquals(0.2961814F, state.movementAttribute().current(), 0);
            if (standing != null) assertEquals(standing, state.horse().standing());
            if (!Boolean.TRUE.equals(standing)) {
                var next = BedrockActorHistoryTest.forward(
                        entry(state), 2, BedrockActorHistoryTest.ground(), new Vec3d(0, 0, 1));
                assertEquals(
                        0.2961814,
                        next.end()
                                .getFirst()
                                .state()
                                .physicalFeetPosition()
                                .subtract(state.physicalFeetPosition())
                                .length(),
                        0.001);
            }
        }
    }

    @Test
    public void vehicleTravelUsesWireCurrentWithoutPlayerSprintOrContextSpeed() {
        var horse = horse();
        var updated = attributes(0.35245588F, false).state(horse);
        var result = BedrockActorHistoryTest.forward(
                entry(updated), 1, BedrockActorHistoryTest.ground(), new Vec3d(0, 0, 1));
        var end = result.end().getFirst().state();
        double distance = end.physicalFeetPosition()
                .subtract(updated.physicalFeetPosition())
                .length();
        assertEquals(0.35245588, distance, 0.00003);
        assertEquals(0.35245588F, end.movementAttribute().current(), 0);
        assertFalse(end.movementAttribute().hasSprintModifier());
        assertEquals(0.1F, horse.movementAttribute().current(), 0);
    }

    @Test
    public void partialUpdatesAndSprintPreserveOtherAttributesAndHistory() {
        var value = BedrockMovementAttributeState.serverValue(0.8F, false);
        var initial =
                new BedrockReplayAttributeEvent(Map.of("minecraft:horse.jump_strength", value), false).state(horse());
        var updated = attributes(0.35F, false).state(initial);
        var detached = BedrockReplaySnapshot.detach(updated);
        var later = attributes(0.2F, false).state(updated);
        assertEquals(0.35F, detached.movementAttribute().current(), 0);
        assertEquals(0.2F, later.movementAttribute().current(), 0);
        assertEquals(0.8F, later.attributes().current("minecraft:horse.jump_strength", 0), 0);
        assertEquals(0.8F, later.applySprintAction(true).attributes().current("minecraft:horse.jump_strength", 0), 0);
        assertEquals(0.1F, initial.movementAttribute().current(), 0);
    }

    @Test
    public void horseStampedReplacementAppliesToTheNextFrameWithoutReplayingItsStampFrame() {
        var history = new BedrockActorHistory();
        var first = BedrockActorHistoryTest.forward(
                entry(attributes(0.2F, false).state(horse())), 1, BedrockActorHistoryTest.ground(), new Vec3d(0, 0, 1));
        history.record(first);
        var rewind = new BedrockMovementRewind(history);
        rewind.queue(1, true, attributes(0.35F, true));
        var published = rewind.apply(null, commit(history.current()));
        var actual = BedrockProfileState.previousState(published.carry());
        var recorded = first.end().getFirst().state();
        assertEquals(recorded.physicalFeetPosition(), actual.physicalFeetPosition());
        assertEquals(recorded.velocity(), actual.velocity());
        assertEquals(0.35F, actual.movementAttribute().current(), 0);
        var expected = BedrockActorHistoryTest.forward(
                        entry(attributes(0.35F, false).state(recorded)),
                        2,
                        BedrockActorHistoryTest.ground(),
                        new Vec3d(0, 0, 1))
                .end()
                .getFirst()
                .state();
        var next = BedrockActorHistoryTest.forward(
                        entry(actual), 2, BedrockActorHistoryTest.ground(), new Vec3d(0, 0, 1))
                .end()
                .getFirst()
                .state();
        assertEquals(expected.physicalFeetPosition(), next.physicalFeetPosition());
        assertEquals(expected.velocity(), next.velocity());
    }

    @Test
    public void speedStampedTicksAgoChangesOnlyTheTicksAfterItsAcknowledgement() {
        // A capture over a delayed connection: 0.3 stamped five ticks before the client acknowledged it.
        // The ticks in between moved at 0.1, and the first tick after it gained 0.98 * (0.3 - 0.1).
        var history = BedrockActorHistoryTest.history(1, 6);
        var before = history.frames();
        var rewind = new BedrockMovementRewind(history);
        rewind.queue(1, true, attributes(0.3F, true));
        var published = rewind.apply(null, commit(history.current()));
        var applied = BedrockProfileState.previousState(published.carry());
        var newest = before.getLast().end().getFirst().state();
        assertEquals(6, applied.clientTick());
        assertEquals(newest.physicalFeetPosition(), applied.physicalFeetPosition());
        assertEquals(newest.velocity(), applied.velocity());
        assertEquals(0.3F, applied.movementAttribute().current(), 0);
        for (int i = 0; i < before.size(); i++) {
            assertEquals(
                    before.get(i).end().getFirst().state().physicalFeetPosition(),
                    history.frames().get(i).end().getFirst().state().physicalFeetPosition());
        }
        var slow = step(newest, 7);
        var fast = step(applied, 7);
        // Positions are floats near x=289: one step resolves to about 3e-5.
        assertEquals(0.98 * (0.3 - 0.1), fast.subtract(slow).length(), 1e-4);
    }

    @Test
    public void speedStampedWithTheNewestTickSlowsTheVeryNextTick() {
        // A capture over a direct connection: 0.05 stamped 882 was acknowledged before input 883, and
        // tick 883 already accelerated by a sixth of what tick 882 did at 0.3.
        var history = new BedrockActorHistory();
        var entry = entry(attributes(0.3F, false)
                .state(BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(289, 82, -86),
                        new Vec3d(0, -0.0784F, 0),
                        BedrockInputFrame.idle(0),
                        BedrockCollisionFlags.ON_GROUND)));
        for (long tick = 1; tick <= 3; tick++) {
            var frame =
                    BedrockActorHistoryTest.forward(entry, tick, BedrockActorHistoryTest.ground(), new Vec3d(0, 0, 1));
            history.record(frame);
            entry = frame.end().getFirst();
        }
        var newest = entry.state();
        var rewind = new BedrockMovementRewind(history);
        rewind.queue(3, true, attributes(0.05F, true));
        var applied = BedrockProfileState.previousState(
                rewind.apply(null, commit(history.current())).carry());
        assertEquals(newest.physicalFeetPosition(), applied.physicalFeetPosition());
        assertEquals(0.05F, applied.movementAttribute().current(), 0);
        double carried = Math.hypot(newest.velocity().x(), newest.velocity().z());
        assertEquals(0.98 * 0.3, step(newest, 4).length() - carried, 1e-4);
        assertEquals(0.98 * 0.05, step(applied, 4).length() - carried, 1e-4);
    }

    @Test
    public void jumpStrengthAcknowledgedWithAReplaySurvivesItAndEveryLaterReplay() {
        // A capture: the restore to 0.42 was acknowledged in the same input window as a correction, and
        // no later packet repeats it.
        for (boolean replayFirst : List.of(true, false)) {
            var history = new BedrockActorHistory();
            var entry = entry(jumpStrength(0.5F)
                    .state(BedrockMovementState.fromPhysicalFeet(
                            new Vec3d(289, 82, -86),
                            new Vec3d(0, -0.0784F, 0),
                            BedrockInputFrame.idle(0),
                            BedrockCollisionFlags.ON_GROUND)));
            for (long tick = 1; tick <= 3; tick++) {
                var frame = BedrockActorHistoryTest.forward(
                        entry, tick, BedrockActorHistoryTest.ground(), new Vec3d(0, 0, 1));
                history.record(frame);
                entry = frame.end().getFirst();
            }
            var rewind = new BedrockMovementRewind(history);
            var correction = new BedrockReplayEvent.Motion(new Vec3d(0, -0.0784F, 0));
            if (replayFirst) rewind.queue(1, true, correction);
            rewind.queue(0, false, jumpStrength(0.42F));
            if (!replayFirst) rewind.queue(1, true, correction);
            var published = rewind.apply(null, commit(history.current()));
            var state = BedrockProfileState.previousState(published.carry());
            assertNotEquals("The correction replayed", entry.state().physicalFeetPosition(), state.physicalFeetPosition());
            assertEquals(0.42F, state.attributes().current("minecraft:horse.jump_strength", 0), 0);
            // It takes effect after the newest frame; the replayed frames before it keep 0.5.
            assertEquals(
                    0.5F,
                    history.frames()
                            .get(1)
                            .end()
                            .getFirst()
                            .state()
                            .attributes()
                            .current("minecraft:horse.jump_strength", 0),
                    0);

            history.record(BedrockActorHistoryTest.forward(
                    history.current().getFirst(), 4, BedrockActorHistoryTest.ground(), new Vec3d(0, 0, 1)));
            rewind.queue(2, true, new BedrockReplayEvent.Motion(new Vec3d(0, -0.0784F, 0)));
            state = BedrockProfileState.previousState(
                    rewind.apply(null, commit(history.current())).carry());
            assertEquals(4, state.clientTick());
            assertEquals(0.42F, state.attributes().current("minecraft:horse.jump_strength", 0), 0);
        }
    }

    @Test
    public void riddenHorseAttributesAcknowledgedWithAReplaySurviveIt() {
        for (boolean replayFirst : List.of(true, false)) {
            var history = new BedrockActorHistory();
            var entry = entry(jumpStrength(0.8F).state(attributes(0.35245588F, false).state(horse())));
            for (long tick = 1; tick <= 3; tick++) {
                var frame = BedrockActorHistoryTest.forward(
                        entry, tick, BedrockActorHistoryTest.ground(), new Vec3d(0, 0, 1));
                history.record(frame);
                entry = frame.end().getFirst();
            }
            var rewind = new BedrockMovementRewind(history);
            var correction = new BedrockReplayEvent.Motion(Vec3d.ZERO);
            if (replayFirst) rewind.queue(1, true, correction);
            rewind.queue(0, false, attributes(0.2961814F, false));
            rewind.queue(0, false, jumpStrength(0.67052144F));
            if (!replayFirst) rewind.queue(1, true, correction);
            var state = BedrockProfileState.previousState(
                    rewind.apply(null, commit(history.current())).carry());
            assertTrue(state.isHorse());
            assertNotEquals("The correction replayed", entry.state().physicalFeetPosition(), state.physicalFeetPosition());
            assertEquals(0.2961814F, state.movementAttribute().current(), 0);
            assertEquals(0.67052144F, state.attributes().current("minecraft:horse.jump_strength", 0), 0);
            var replayed = history.frames().get(1).end().getFirst().state();
            assertEquals(0.35245588F, replayed.movementAttribute().current(), 0);
            assertEquals(0.8F, replayed.attributes().current("minecraft:horse.jump_strength", 0), 0);
        }
    }

    private static BedrockReplayAttributeEvent jumpStrength(float value) {
        return new BedrockReplayAttributeEvent(
                Map.of("minecraft:horse.jump_strength", BedrockMovementAttributeState.serverValue(value, false)), false);
    }

    private static Vec3d step(BedrockMovementState state, long tick) {
        var end = BedrockActorHistoryTest.forward(
                        entry(state), tick, BedrockActorHistoryTest.ground(), new Vec3d(0, 0, 1))
                .end()
                .getFirst()
                .state();
        var moved = end.physicalFeetPosition().subtract(state.physicalFeetPosition());
        return new Vec3d(moved.x(), 0, moved.z());
    }

    @Test
    public void initialVehicleStateUsesPreviouslyAcknowledgedWireAttributes() {
        var horse = org.mockito.Mockito.mock(ac.cult.cultac.utils.data.packetentity.PacketEntityHorse.class);
        horse.movementSpeedAttribute = 0.9F;
        horse.jumpStrength = 1.9;
        horse.bedrockAttributes = ac.cult.cultac.bedrock.prediction.state.BedrockActorAttributes.EMPTY.replace(Map.of(
                "minecraft:movement", BedrockMovementAttributeState.serverValue(0.35F, false),
                "minecraft:horse.jump_strength", BedrockMovementAttributeState.serverValue(0.8F, false)));
        var context = org.mockito.Mockito.mock(ac.cult.cultac.checks.impl.prediction.SimulationContext.class);
        org.mockito.Mockito.when(context.getVehicle()).thenReturn(horse);
        org.mockito.Mockito.when(context.getStart()).thenReturn(new ac.cult.cultac.utils.math.Vec3(289, 82, -86));
        var world = BedrockActorHistoryTest.ground().movementContext();
        var initial = BedrockInitialStateFactory.resolve(context, BedrockInputFrame.idle(1), world, null, null);
        assertEquals(0.35F, initial.movementAttribute().current(), 0);
        assertEquals(0.8F, initial.attributes().current("minecraft:horse.jump_strength", 0), 0);
        var attributes = initial.attributes().apply(world.attributeState());
        assertEquals(0.35F, attributes.baseMovementSpeed(), 0);
        assertEquals(0.8F, attributes.jumpStrength(), 0);
    }

    @Test
    public void horseJumpUsesWireJumpStrength() {
        var state = new BedrockReplayAttributeEvent(
                        Map.of("minecraft:horse.jump_strength", BedrockMovementAttributeState.serverValue(0.8F, false)),
                        false)
                .state(horse());
        state = state.withHorse(state.horse().forFrame(0, false, 90));
        var next = BedrockActorHistoryTest.forward(entry(state), 1, BedrockActorHistoryTest.ground(), Vec3d.ZERO)
                .end()
                .getFirst()
                .state();
        assertEquals(
                0.8,
                next.physicalFeetPosition().y() - state.physicalFeetPosition().y(),
                0.00003);
    }

    @Test
    public void loginReplacementSupersedesGeyserHorseJumpFallback() {
        var state = new BedrockReplayAttributeEvent(
                        Map.of("minecraft:horse.jump_strength", BedrockMovementAttributeState.serverValue(0.5F, false)),
                        false)
                .state(horse());
        state = new BedrockReplayAttributeEvent(
                        Map.of(
                                "minecraft:horse.jump_strength",
                                        new BedrockMovementAttributeState(0.67052144F, 0, 2, 0, 2, 0.7F, List.of()),
                                "minecraft:movement",
                                        new BedrockMovementAttributeState(
                                                0.2961814F, 0, 1024, 0, 1024, 0.1F, List.of())),
                        false)
                .state(state);
        state = state.withHorse(state.horse().forFrame(0, false, 90));
        var next = BedrockActorHistoryTest.forward(entry(state), 1, BedrockActorHistoryTest.ground(), Vec3d.ZERO)
                .end()
                .getFirst()
                .state();
        assertEquals(
                0.67052144,
                next.physicalFeetPosition().y() - state.physicalFeetPosition().y(),
                0.00003);
    }

    @Test
    public void historicalConfirmationPreservesMatchingInstancesInAPartialMismatch() {
        var state = BedrockMovementState.fromPhysicalFeet(
                        Vec3d.ZERO, Vec3d.ZERO, BedrockInputFrame.idle(0), BedrockCollisionFlags.ON_GROUND)
                .applySprintAction(true);
        var packetMovement = new BedrockMovementAttributeState(
                state.movementAttribute().current(), 0, 1024, 0, 1024, 0.1F, List.of());
        var event = new BedrockReplayAttributeEvent(
                Map.of(
                        "minecraft:movement",
                        packetMovement,
                        "minecraft:horse.jump_strength",
                        BedrockMovementAttributeState.serverValue(0.8F, false)),
                true);
        var result = event.state(state);
        assertTrue(result.movementAttribute().hasSprintModifier());
        assertEquals(0.8F, result.attributes().current("minecraft:horse.jump_strength", 0), 0);
    }

    private static BedrockMovementState horse() {
        return BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(289, 82, -86), Vec3d.ZERO, BedrockInputFrame.idle(0), BedrockCollisionFlags.ON_GROUND)
                .withHorse(new BedrockHorseState(null, false, 0, false, false, 0, -1, false, 0));
    }

    private static BedrockReplayAttributeEvent attributes(float speed, boolean historical) {
        return new BedrockReplayAttributeEvent(
                Map.of(
                        "minecraft:movement",
                        new BedrockMovementAttributeState(speed, 0, 1024, 0, 1024, 0.1F, List.of())),
                historical);
    }

    private static BedrockProfileState.Entry entry(BedrockMovementState state) {
        return new BedrockProfileState.Entry(state, BedrockMobJumpComponentState.DEFAULT);
    }

    private static PredictionCommit commit(List<BedrockProfileState.Entry> entries) {
        return new PredictionCommit(
                new BedrockNextTickStates(entries), BedrockNextTickVelocityDerivation.profileStateVelocities(entries));
    }
}
