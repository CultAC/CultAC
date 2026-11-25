package ac.cult.cultac.bedrock.prediction.simulation.frame;

import static org.junit.Assert.assertEquals;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatProperties;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * A capture shows a leashed boat rising from rest in a pool: each tick's move used the velocity the previous tick
 * left, and the buoyancy after the move left 0.038036, 0.048653 and then 0.080902, the full lift for its depth at
 * 119.40073, where an unleashed boat leaves 0.055936.
 */
public class BedrockBoatLeashedBuoyancyTest {
    private static final BlockCollisionWorld POOL = pool();

    @Test
    public void aLeashedBoatRisingTakesTheFullLiftForItsDepth() {
        assertEquals(0.080902F, buoyancy(true, 119.40073F, 0.048653F), 1.0E-6F);
    }

    @Test
    public void aLeashedBoatThatGravityLeavesSinkingTakesTheDampedLift() {
        assertEquals(0.038036F, buoyancy(true, 119.29148F, 0.02256F), 1.0E-6F);
        assertEquals(0.048653F, buoyancy(true, 119.35207F, 0.038036F), 1.0E-6F);
    }

    @Test
    public void anUnleashedBoatRisingTakesTheDampedLift() {
        assertEquals(0.055936F, buoyancy(false, 119.40073F, 0.048653F), 1.0E-6F);
    }

    private static float buoyancy(boolean leashed, float reportedY, float velocityY) {
        Vec3d feet = new Vec3d(-49.5F, reportedY - BedrockBoatProperties.ORIGIN_HEIGHT, 54.5F);
        Vec3d velocity = new Vec3d(0, velocityY, 0);
        var initial = BedrockBoatProperties.initial(12);
        var properties = new BedrockBoatProperties(
                initial.runtimeId(),
                initial.dimensions(),
                initial.buoyant(),
                initial.gravity(),
                initial.baseBuoyancy(),
                initial.liquids(),
                initial.outOfControl(),
                leashed,
                initial.seat());
        BedrockMovementState boat = BedrockMovementState.fromPhysicalFeet(
                        feet, velocity, BedrockInputFrame.idle(1L), BedrockCollisionFlags.AIR)
                .withBoat(new BedrockBoatState(
                        null,
                        properties,
                        0,
                        0,
                        0,
                        BedrockBoatState.Paddle.INITIAL,
                        BedrockBoatState.Paddle.INITIAL,
                        BedrockBoatState.PaddleInput.ANALOG))
                .withPhysicalFeetPosition(feet, 0)
                .withPlayerDimensions(BedrockBoatProperties.DIMENSIONS, true);
        return (float) BedrockBoatMovement.afterMove(boat, POOL, feet, velocity).y();
    }

    /** Water from y 117 to 119 around the boat. */
    private static BlockCollisionWorld pool() {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (int x = -52; x <= -47; x++) {
            for (int z = 52; z <= 57; z++) {
                for (int y = 117; y <= 119; y++) {
                    blocks.add(PlacedBlockCollision.manual(
                            new BlockPosition(x, y, z), "minecraft:water[level=0]", "minecraft:water", List.of()));
                }
            }
        }
        return new BlockCollisionWorld(blocks);
    }
}
