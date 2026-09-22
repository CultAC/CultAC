package ac.cult.cultac.bedrock.prediction.simulation;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.*;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInput;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.*;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BedrockFluidHopCarryTest {
    @Test
    public void wallContactKeepsNormalWaterCarryBesideHop() {
        var world = new BlockCollisionWorld(List.of(
            PlacedBlockCollision.manual(new BlockPosition(0, 0, 0), "minecraft:stone", "minecraft:stone",
                List.of(new WorldCollisionBox(0, 0, 0, 1, 1, 1))),
            PlacedBlockCollision.manual(new BlockPosition(-1, 1, 0), "minecraft:stone_slab[type=bottom]", "minecraft:stone_slab",
                List.of(new WorldCollisionBox(-1, 1, 0, 0, 1.5, 1))),
            PlacedBlockCollision.manual(new BlockPosition(0, 1, 0), "minecraft:water[level=7]", "minecraft:water",
                Map.of("liquid_depth", 7), List.of())));
        var context = new BedrockMovementContext(BedrockEffectState.NONE, AttributeState.DEFAULT,
            new WorldContactState(Medium.WATER, FluidState.NONE, world), EquipmentState.NONE,
            EntityContactState.NONE, MovementModifierState.NONE, PlayerDimensionsState.DEFAULT);
        var previous = BedrockMovementState.fromPhysicalFeet(new Vec3d(0.328338623046875, 1, 0.5),
            new Vec3d(-0.0490938739, -0.005, 0.0498698631), BedrockInputFrame.idle(47406),
            BedrockCollisionFlags.AIR, Medium.WATER).withWaterTravelFlag(true);
        var result = BedrockSimulation.move(previous, BedrockInputFrame.idle(47407), context,
            BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE, false, BedrockSimulation.DEFAULT_MAX_AUTO_STEP);
        assertTrue(result.selectedWaterTravel());
        assertTrue(result.predictedState().collisionFlags().liquidClimbOut());
        assertEquals(0.3F, result.predictedVelocity().y(), 1e-9);
        var carries = BedrockForwardTick.finish(result, result.predictedState());
        assertEquals(2, carries.size());
        assertTrue("Normal water carry must survive a simulated hop", carries.stream().anyMatch(state ->
            !state.collisionFlags().liquidClimbOut() && Math.abs(state.velocity().y() + 0.005) < 1e-7));
        assertTrue(carries.stream().anyMatch(state -> state.collisionFlags().liquidClimbOut()
            && Math.abs(state.velocity().y() - 0.3F) < 1e-7));
        assertTrue(carries.stream().allMatch(state -> state.physicalFeetPosition().equals(result.predictedPosition())));
    }
}
