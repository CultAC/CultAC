package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.*;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.*;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class BedrockGeyserReplayTest {
    @Test
    public void aHistoricalEffectLaunchesOnceAndExpiresDuringReplay() {
        var world = column();
        var history = new BedrockActorHistory();
        var state = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(0.5, 24.5, 0.5), Vec3d.ZERO, BedrockInputFrame.idle(0), BedrockCollisionFlags.AIR);
        for (int tick = 1; tick <= 3; tick++) {
            var frame = forward(state, tick, world);
            history.record(frame);
            state = frame.end().getFirst().state();
        }
        var expected = new BedrockReplayEvent.GeyserBoost(1)
                .state(history.frames().getFirst().end().getFirst().state());
        for (int tick = 2; tick <= 3; tick++)
            expected = forward(expected, tick, world).end().getFirst().state();
        var replayed = history.apply(1, new BedrockReplayEvent.GeyserBoost(1), (previous, recorded) -> recorded)
                .getFirst()
                .state();
        assertEquals(expected.physicalFeetPosition(), replayed.physicalFeetPosition());
        assertEquals(expected.velocity(), replayed.velocity());
        assertEquals(0, replayed.geyserBoostTicks());
        assertTrue(replayed.physicalFeetPosition().y()
                > state.physicalFeetPosition().y() + 0.1);
    }

    @Test
    public void anUntimedEffectAndItsReplacementAreRetainedInHistory() {
        var history = new BedrockActorHistory();
        var state = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(0.5, 30, 0.5), Vec3d.ZERO, BedrockInputFrame.idle(0), BedrockCollisionFlags.AIR);
        history.record(forward(state, 1, column()));
        history.ordinary(new BedrockReplayEvent.GeyserBoost(-1));
        assertEquals(-1, history.current().getFirst().state().geyserBoostTicks());
        history.ordinary(new BedrockReplayEvent.GeyserBoost(0));
        assertEquals(1, history.current().getFirst().state().geyserBoostTicks());
        var replay = history.apply(1, new BedrockReplayEvent.Motion(Vec3d.ZERO), (previous, world) -> world);
        assertEquals(1, replay.getFirst().state().geyserBoostTicks());
    }

    private static BedrockActorHistory.Frame forward(BedrockMovementState state, int tick, BedrockWorldSnapshot world) {
        var frame = BedrockInputFrame.idle(tick);
        var input = new BedrockSimulation.Input(
                state,
                frame,
                frame.intent(),
                world,
                false,
                0,
                BedrockMobJumpComponentState.DEFAULT,
                true,
                false,
                Vec3d.ZERO);
        var candidate = BedrockForwardTick.simulate(input).getFirst();
        var movement = candidate.movementResult();
        var end = List.of(new BedrockProfileState.Entry(
                BedrockForwardTick.finish(movement, movement.predictedState()), candidate.mobJumpComponent()));
        return new BedrockActorHistory.Frame(
                tick, input, end, end, null, Vec3d.ZERO, Vec3d.ZERO, movement.predictedPosition(), null, List.of());
    }

    private static BedrockWorldSnapshot column() {
        var blocks = new ArrayList<PlacedBlockCollision>();
        blocks.add(PlacedBlockCollision.manual(
                new BlockPosition(0, 0, 0),
                "minecraft:potent_sulfur",
                "minecraft:potent_sulfur",
                Map.of("potent_sulfur_state", "continuous"),
                List.of(new WorldCollisionBox(0, 0, 0, 1, 1, 1))));
        for (int y = 1; y <= 4; y++)
            blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(0, y, 0),
                    "minecraft:water",
                    "minecraft:water",
                    Map.of("liquid_depth", 0),
                    List.of()));
        var context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
        return BedrockWorldSnapshot.fromContext(context);
    }
}
