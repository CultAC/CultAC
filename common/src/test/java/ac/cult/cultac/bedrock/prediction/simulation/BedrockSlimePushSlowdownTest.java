package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInput;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class BedrockSlimePushSlowdownTest {
    private static final Vec3d FEET = new Vec3d(325.9498596191406D, 84.94159698486328D, -51.17359161376953D);

    @Test
    public void slimeSlowsTheVelocityTheBlockPushGivesAnActorStandingInIt() {
        // Live r3, tick 1931: corrected onto the ground with the box in the top of a pistoned slime, at rest.
        BedrockMovementState next = endOfTick("minecraft:slime_block", "minecraft:slime");

        assertEquals(FEET.x(), next.physicalFeetPosition().x(), 0.0D);
        assertEquals(FEET.y(), next.physicalFeetPosition().y(), 0.0D);
        assertEquals(FEET.z(), next.physicalFeetPosition().z(), 0.0D);
        // The client reported (0.023086142, -0.0784, -0.03456774): the slime took the push out of it.
        assertEquals(0.023086142D, next.velocity().x(), 1.0E-8D);
        assertEquals(-0.0784D, next.velocity().y(), 1.0E-7D);
        assertEquals(-0.03456774D, next.velocity().z(), 1.0E-8D);
    }

    @Test
    public void anotherBlockLeavesTheWholePush() {
        // Without the slime the push alone is what the client carried into the following ticks.
        BedrockMovementState next = endOfTick("minecraft:stone", "minecraft:stone");

        assertEquals(0.055538256D, next.velocity().x(), 1.0E-8D);
        assertEquals(-0.0831595D, next.velocity().z(), 1.0E-7D);
    }

    private static BedrockMovementState endOfTick(String javaState, String bedrockIdentifier) {
        BedrockMovementState previous = BedrockMovementState.fromPhysicalFeet(
                FEET, Vec3d.ZERO, BedrockInputFrame.idle(1930L), BedrockCollisionFlags.ON_GROUND);
        BedrockInputFrame frame = new BedrockInputFrame(1931L, -137.79904F, 43.998764F, false, false, false, Set.of());
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        BlockPosition overlapped = new BlockPosition(325, 84, -51);
        blocks.add(PlacedBlockCollision.manual(overlapped, javaState, bedrockIdentifier, List.of(cube(overlapped))));
        for (int x = 324; x <= 325; x++) {
            for (int z = -53; z <= -50; z++) {
                BlockPosition floor = new BlockPosition(x, 83, z);
                if (x == 325 && z == -51) continue;
                blocks.add(PlacedBlockCollision.manual(
                        floor, "minecraft:obsidian", "minecraft:obsidian", List.of(cube(floor))));
            }
        }
        BedrockMovementContext context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                new MovementModifierState(true, true, false, true, .05, false, false, false, false, .35, 0),
                PlayerDimensionsState.DEFAULT);
        BedrockMovementResult result = BedrockSimulation.move(
                previous,
                frame,
                context,
                BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP);
        assertTrue(result.predictedState().collisionFlags().onGround());
        return BedrockForwardTick.finish(result, result.predictedState());
    }

    private static WorldCollisionBox cube(BlockPosition position) {
        return new WorldCollisionBox(
                position.x(),
                position.y(),
                position.z(),
                position.x() + 1.0D,
                position.y() + 1.0D,
                position.z() + 1.0D);
    }
}
