package ac.cult.cultac.bedrock.prediction.state;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.integration.BedrockReplayContextEvent;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class BedrockMovementAttributeStateTest {
    @Test public void replacementCanLeaveBoostedCurrentWithoutSprintModifier() {
        var running = initial().applySprintAction(true);
        var replacement = new BedrockMovementAttributeState(0.13000001F, 0, 1024, 0, 1024, 0.1F, List.of());
        var replaced = running.withMovementAttribute(running.movementAttribute().replace(replacement));
        var stopped = replaced.applySprintAction(false);
        assertFalse(stopped.sprinting());
        assertFalse(stopped.movementAttribute().hasSprintModifier());
        assertEquals(0.13000001F, stopped.movementAttribute().current(), 0);
        assertSame(replaced.movementAttribute(), stopped.movementAttribute());
    }

    @Test public void replayedStartRecreatesRemovableModifier() {
        var walking = initial().withMovementAttribute(new BedrockMovementAttributeState(
                0.13000001F, 0, 1024, 0, 1024, 0.1F, List.of()));
        var replayed = walking.applySprintAction(true);
        assertTrue(replayed.movementAttribute().hasSprintModifier());
        assertEquals(0.1F, replayed.applySprintAction(false).movementAttribute().current(), 0);
    }

    @Test public void actionsAreOrderedAndHeldFlagDoesNotUndoStop() {
        var frame = new BedrockInputFrame(1, 0, 0, false, false, true,
                Set.of("SPRINTING", "START_SPRINTING", "STOP_SPRINTING"));
        var stopped = initial().applySprintActions(frame.intent().sprint());
        assertFalse(stopped.sprinting());
        assertFalse(stopped.movementAttribute().hasSprintModifier());
        assertEquals(0.1F, stopped.movementAttribute().current(), 0);
    }

    @Test public void modifierNoOpsPreserveIndependentCurrentAndDefaults() {
        var running = BedrockMovementAttributeState.serverValue(0.2F, true);
        assertSame(running, running.addSprint());
        assertEquals(0.2F, running.removeSprint().current(), 0);
        var stopped = running.removeSprint();
        assertSame(stopped, stopped.removeSprint());
    }

    @Test public void matchingHistoricalCurrentSkipsModifierReplacement() {
        var running = initial().applySprintAction(true);
        var absent = new BedrockMovementAttributeState(running.movementAttribute().current(), 0, 1024,
                0, 1024, 0.1F, List.of());
        var event = new BedrockReplayContextEvent(Map.of(), null, null, -1, null, null, absent, true);
        assertSame(running, event.state(running));
        assertFalse(event.ordinary().state(running).movementAttribute().hasSprintModifier());
    }

    @Test public void packetLimitsClampReplacementButDefaultsDriveLaterRecalculation() {
        var supplied = new BedrockMovementAttributeState(0.3F, 0, 0.25F, 0, 1, 0.2F,
                List.of(BedrockMovementAttributeState.SPRINT));
        var received = BedrockMovementAttributeState.DEFAULT.replace(supplied);
        assertEquals(0.25F, received.current(), 0);
        assertEquals(0.2F, received.removeSprint().current(), 0);
    }

    private static BedrockMovementState initial() {
        return BedrockMovementState.fromPhysicalFeet(Vec3d.ZERO, Vec3d.ZERO,
                BedrockInputFrame.idle(0), BedrockCollisionFlags.ON_GROUND);
    }
}
