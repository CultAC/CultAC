package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState.Entry;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockSnapshotResolver;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * A tick senses liquid at the box it starts from. A correction that places the actor in falling water replays the
 * following ticks with the falling current there, not the still water the recorded ticks started in.
 */
public final class BedrockReplayLiquidSensingTest {
    private static final BedrockWorldSnapshot WORLD = world();

    @Test
    public void aTickReplayedFromAPlacementSensesTheLiquidThere() {
        var history = new BedrockActorHistory();
        var entry = new Entry(
                BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(0.5, 0.2, 0.5), Vec3d.ZERO, BedrockInputFrame.idle(0), BedrockCollisionFlags.AIR),
                BedrockMobJumpComponentState.DEFAULT);
        for (long tick = 1; tick <= 3; tick++) {
            var frame = forward(entry, tick);
            history.record(frame);
            entry = frame.end().getFirst();
        }
        var placement = new BedrockReplayEvent.Reposition(
                new Vec3d(10.5, 0.2, 0.5),
                0,
                0,
                false,
                history.frames().getFirst().end().getFirst().state().coordinateFrame());

        var replayed =
                history.apply(1, placement, (state, world) -> world).getFirst().state();

        var placed = new Entry(
                placement.state(history.frames().getFirst().end().getFirst().state()),
                BedrockMobJumpComponentState.DEFAULT);
        var expected = forward(forward(placed, 2).end().getFirst(), 3).end().getFirst().state();
        assertEquals(expected.physicalFeetPosition(), replayed.physicalFeetPosition());
        assertEquals(expected.velocity(), replayed.velocity());
        // The falling current pulls the actor down further than still water does.
        var still = forward(forward(entry(placed, new Vec3d(0.5, 0.2, 0.5)), 2).end().getFirst(), 3)
                .end()
                .getFirst()
                .state();
        assertNotEquals(still.velocity().y(), replayed.velocity().y(), 1.0E-4);
    }

    private static Entry entry(Entry entry, Vec3d feet) {
        return new Entry(entry.state().withPhysicalFeetPosition(feet, 0), entry.mobJumpComponent());
    }

    /** A recorded tick, its snapshot sensed at the state it starts from as a live tick's is. */
    private static BedrockActorHistory.Frame forward(Entry previous, long tick) {
        var input = new BedrockInputFrame(tick, 0, 0, false, false, false, Set.of());
        var request = new BedrockSimulation.Input(
                previous.state(),
                input,
                input.intent(),
                BedrockSnapshotResolver.forState(WORLD, previous.state(), input),
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                previous.mobJumpComponent(),
                true,
                false,
                Vec3d.ZERO);
        var movement = BedrockForwardTick.simulate(request).getFirst();
        var state = movement.movementResult().predictedState();
        var entries = BedrockForwardTick.finish(movement.movementResult(), state).stream()
                .map(next -> new Entry(next, movement.mobJumpComponent()))
                .toList();
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

    private static BedrockWorldSnapshot world() {
        var blocks = new ArrayList<PlacedBlockCollision>();
        for (int y = -1; y <= 3; y++) {
            blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(0, y, 0),
                    "minecraft:water",
                    "minecraft:water",
                    Map.of("liquid_depth", 0),
                    List.of()));
            blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(10, y, 0),
                    "minecraft:flowing_water",
                    "minecraft:flowing_water",
                    Map.of("liquid_depth", 8, "flow_x", 0.0D, "flow_y", -1.0D, "flow_z", 0.0D),
                    List.of()));
        }
        return BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT));
    }
}
