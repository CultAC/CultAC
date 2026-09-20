package ac.cult.cultac.bedrock.prediction.integration;

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
import static org.junit.Assert.*;

public class BedrockActorAttributesTest {
    @Test public void vehicleTravelUsesWireCurrentWithoutPlayerSprintOrContextSpeed() {
        var horse = horse();
        var updated = attributes(0.35245588F, false).state(horse);
        var result = BedrockActorHistoryTest.forward(entry(updated), 1, BedrockActorHistoryTest.ground(), new Vec3d(0, 0, 1));
        var end = result.end().getFirst().state();
        double distance = end.physicalFeetPosition().subtract(updated.physicalFeetPosition()).length();
        assertEquals(0.35245588, distance, 0.00003);
        assertEquals(0.35245588F, end.movementAttribute().current(), 0);
        assertFalse(end.movementAttribute().hasSprintModifier());
        assertEquals(0.1F, horse.movementAttribute().current(), 0);
    }

    @Test public void partialUpdatesAndSprintPreserveOtherAttributesAndHistory() {
        var value = BedrockMovementAttributeState.serverValue(0.8F, false);
        var initial = new BedrockReplayAttributeEvent(Map.of("minecraft:horse.jump_strength", value), false).state(horse());
        var updated = attributes(0.35F, false).state(initial);
        var detached = BedrockReplaySnapshot.detach(updated);
        var later = attributes(0.2F, false).state(updated);
        assertEquals(0.35F, detached.movementAttribute().current(), 0);
        assertEquals(0.2F, later.movementAttribute().current(), 0);
        assertEquals(0.8F, later.attributes().current("minecraft:horse.jump_strength", 0), 0);
        assertEquals(0.8F, later.applySprintAction(true).attributes().current("minecraft:horse.jump_strength", 0), 0);
        assertEquals(0.1F, initial.movementAttribute().current(), 0);
    }

    @Test public void horseHistoricalReplacementWaitsThenReplaysUsingItsOwnAttributes() {
        var history = new BedrockActorHistory();
        var first = BedrockActorHistoryTest.forward(entry(attributes(0.2F, false).state(horse())), 1,
                BedrockActorHistoryTest.ground(), new Vec3d(0, 0, 1));
        history.record(first);
        var rewind = new BedrockMovementRewind(history);
        var current = commit(history.current());
        rewind.queue(1, true, attributes(0.35F, true));
        assertSame(current, rewind.apply(null, current));
        var second = BedrockActorHistoryTest.forward(first.end().getFirst(), 2,
                BedrockActorHistoryTest.ground(), new Vec3d(0, 0, 1));
        history.record(second);
        var published = rewind.apply(null, commit(history.current()));
        var actual = BedrockProfileState.previousState(published.carry());
        var expected = BedrockActorHistoryTest.forward(entry(attributes(0.35F, false).state(first.end().getFirst().state())), 2,
                BedrockActorHistoryTest.ground(), new Vec3d(0, 0, 1)).end().getFirst().state();
        assertEquals(expected.physicalFeetPosition(), actual.physicalFeetPosition());
        assertEquals(expected.velocity(), actual.velocity());
        assertEquals(0.35F, actual.movementAttribute().current(), 0);
        assertEquals(0.2F, second.end().getFirst().state().movementAttribute().current(), 0);
    }

    @Test public void initialVehicleStateUsesPreviouslyAcknowledgedWireAttributes() {
        var horse = org.mockito.Mockito.mock(ac.cult.cultac.utils.data.packetentity.PacketEntityHorse.class);
        horse.movementSpeedAttribute = 0.9F;
        horse.jumpStrength = 1.9;
        horse.bedrockAttributes = ac.cult.cultac.bedrock.prediction.state.BedrockActorAttributes.EMPTY.replace(Map.of(
                "minecraft:movement", BedrockMovementAttributeState.serverValue(0.35F, false),
                "minecraft:horse.jump_strength", BedrockMovementAttributeState.serverValue(0.8F, false)));
        var context = org.mockito.Mockito.mock(ac.cult.cultac.checks.impl.prediction.SimulationContext.class);
        org.mockito.Mockito.when(context.getVehicle()).thenReturn(horse);
        org.mockito.Mockito.when(context.getStart()).thenReturn(new net.minecraft.world.phys.Vec3(289, 82, -86));
        var world = BedrockActorHistoryTest.ground().movementContext();
        var initial = BedrockInitialStateFactory.resolve(context, BedrockInputFrame.idle(1), world, null, null);
        assertEquals(0.35F, initial.movementAttribute().current(), 0);
        assertEquals(0.8F, initial.attributes().current("minecraft:horse.jump_strength", 0), 0);
        var attributes = initial.attributes().apply(world.attributeState());
        assertEquals(0.35F, attributes.baseMovementSpeed(), 0);
        assertEquals(0.8F, attributes.jumpStrength(), 0);
    }

    @Test public void horseJumpUsesWireJumpStrength() {
        var state = new BedrockReplayAttributeEvent(Map.of("minecraft:horse.jump_strength",
                BedrockMovementAttributeState.serverValue(0.8F, false)), false).state(horse());
        state = state.withHorse(state.horse().forFrame(0, false, 90));
        var next = BedrockActorHistoryTest.forward(entry(state), 1, BedrockActorHistoryTest.ground(), Vec3d.ZERO)
                .end().getFirst().state();
        assertEquals(0.8, next.physicalFeetPosition().y() - state.physicalFeetPosition().y(), 0.00003);
    }

    @Test public void loginReplacementSupersedesGeyserHorseJumpFallback() {
        var state = new BedrockReplayAttributeEvent(Map.of("minecraft:horse.jump_strength",
                BedrockMovementAttributeState.serverValue(0.5F, false)), false).state(horse());
        state = new BedrockReplayAttributeEvent(Map.of(
                "minecraft:horse.jump_strength", new BedrockMovementAttributeState(
                        0.67052144F, 0, 2, 0, 2, 0.7F, List.of()),
                "minecraft:movement", new BedrockMovementAttributeState(
                        0.2961814F, 0, 1024, 0, 1024, 0.1F, List.of())), false).state(state);
        state = state.withHorse(state.horse().forFrame(0, false, 90));
        var next = BedrockActorHistoryTest.forward(entry(state), 1, BedrockActorHistoryTest.ground(), Vec3d.ZERO)
                .end().getFirst().state();
        assertEquals(0.67052144, next.physicalFeetPosition().y() - state.physicalFeetPosition().y(), 0.00003);
    }

    @Test public void historicalConfirmationPreservesMatchingInstancesInAPartialMismatch() {
        var state = BedrockMovementState.fromPhysicalFeet(Vec3d.ZERO, Vec3d.ZERO,
                BedrockInputFrame.idle(0), BedrockCollisionFlags.ON_GROUND).applySprintAction(true);
        var packetMovement = new BedrockMovementAttributeState(state.movementAttribute().current(),
                0, 1024, 0, 1024, 0.1F, List.of());
        var event = new BedrockReplayAttributeEvent(Map.of("minecraft:movement", packetMovement,
                "minecraft:horse.jump_strength", BedrockMovementAttributeState.serverValue(0.8F, false)), true);
        var result = event.state(state);
        assertTrue(result.movementAttribute().hasSprintModifier());
        assertEquals(0.8F, result.attributes().current("minecraft:horse.jump_strength", 0), 0);
    }

    private static BedrockMovementState horse() {
        return BedrockMovementState.fromPhysicalFeet(new Vec3d(289, 82, -86), Vec3d.ZERO,
                BedrockInputFrame.idle(0), BedrockCollisionFlags.ON_GROUND)
                .withHorse(new BedrockHorseState(null, false, 0, false, false, 0, -1, false, 0));
    }
    private static BedrockReplayAttributeEvent attributes(float speed, boolean historical) {
        return new BedrockReplayAttributeEvent(Map.of("minecraft:movement", new BedrockMovementAttributeState(
                speed, 0, 1024, 0, 1024, 0.1F, List.of())), historical);
    }
    private static BedrockProfileState.Entry entry(BedrockMovementState state) {
        return new BedrockProfileState.Entry(state, BedrockMobJumpComponentState.DEFAULT);
    }
    private static PredictionCommit commit(List<BedrockProfileState.Entry> entries) {
        return new PredictionCommit(new BedrockNextTickStates(entries), BedrockNextTickVelocityDerivation.profileStateVelocities(entries));
    }
}
