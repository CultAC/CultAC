package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertEquals;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState.Entry;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatProperties;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatState;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * f40-v40-diag (1.26.40.5, vcollide boatrow): a boat rowed on ice into parked boats passes through them on the client,
 * and a live tick of the ridden boat meets no solid actor. A correction's replay met the boats the player had stood
 * beside before mounting, stopped the replayed boat at the first one and flagged the client 1.63 off its own path.
 */
public final class BedrockReplayBoatActorsTest {
    private static final Vec3d FEET = new Vec3d(41.5, 120.0, 25.5);

    @Test
    public void aReplayedRiddenBoatMeetsNoSolidActor() {
        assertEquals(0, actorSamples(boat()));
    }

    @Test
    public void aReplayedPlayerOnFootStillMeetsThem() {
        assertEquals(1, actorSamples(BedrockMovementState.fromPhysicalFeet(
                FEET, new Vec3d(0.0, -0.0784, 0.0), BedrockInputFrame.idle(0), BedrockCollisionFlags.ON_GROUND)));
    }

    private static int actorSamples(BedrockMovementState state) {
        var entry = new Entry(state, BedrockMobJumpComponentState.DEFAULT);
        var input = BedrockInputFrame.idle(1);
        var request = new BedrockSimulation.Input(
                state,
                input,
                input.intent(),
                floor(),
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                BedrockMobJumpComponentState.DEFAULT,
                true,
                false,
                Vec3d.ZERO);
        var frame = new BedrockActorHistory.Frame(
                1, request, List.of(entry), List.of(entry), null, Vec3d.ZERO, Vec3d.ZERO, FEET, null, List.of());
        var samples = new AtomicInteger();
        BedrockReplayTick.advance(frame, List.of(entry), List.of(), (s, world) -> world, (recorded, s, world) -> {
            samples.incrementAndGet();
            return world;
        });
        return samples.get();
    }

    private static BedrockMovementState boat() {
        return BedrockMovementState.fromPhysicalFeet(
                        FEET, new Vec3d(0.0, -0.0392, 0.81), BedrockInputFrame.idle(0), BedrockCollisionFlags.ON_GROUND)
                .withBoat(new BedrockBoatState(
                        null,
                        BedrockBoatProperties.initial(12),
                        0,
                        0,
                        0,
                        BedrockBoatState.Paddle.INITIAL,
                        BedrockBoatState.Paddle.INITIAL,
                        BedrockBoatState.PaddleInput.ANALOG));
    }

    private static BedrockWorldSnapshot floor() {
        var blocks = new ArrayList<PlacedBlockCollision>();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                int bx = (int) Math.floor(FEET.x()) + x;
                int bz = (int) Math.floor(FEET.z()) + z;
                blocks.add(PlacedBlockCollision.manual(
                        new BlockPosition(bx, 119, bz),
                        "minecraft:packed_ice",
                        "minecraft:packed_ice",
                        List.of(new WorldCollisionBox(bx, 119, bz, bx + 1, 120, bz + 1))));
            }
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
