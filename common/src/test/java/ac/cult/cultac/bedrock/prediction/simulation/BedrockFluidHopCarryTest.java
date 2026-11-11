package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.*;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.*;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInput;
import ac.cult.cultac.bedrock.prediction.simulation.postmove.BedrockLiquidClimbOutMovement;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.*;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class BedrockFluidHopCarryTest {
    @Test
    public void wallContactInsideAWaterCellCarriesTheOneSwimState() {
        var world = new BlockCollisionWorld(List.of(
                PlacedBlockCollision.manual(
                        new BlockPosition(0, 0, 0),
                        "minecraft:stone",
                        "minecraft:stone",
                        List.of(new WorldCollisionBox(0, 0, 0, 1, 1, 1))),
                PlacedBlockCollision.manual(
                        new BlockPosition(-1, 1, 0),
                        "minecraft:stone_slab[type=bottom]",
                        "minecraft:stone_slab",
                        List.of(new WorldCollisionBox(-1, 1, 0, 0, 1.5, 1))),
                PlacedBlockCollision.manual(
                        new BlockPosition(0, 1, 0),
                        "minecraft:water[level=7]",
                        "minecraft:water",
                        Map.of("liquid_depth", 7),
                        List.of())));
        var context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.WATER, FluidState.NONE, world),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
        var previous = BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(0.328338623046875, 1, 0.5),
                        new Vec3d(-0.0490938739, -0.005, 0.0498698631),
                        BedrockInputFrame.idle(47406),
                        BedrockCollisionFlags.AIR,
                        Medium.WATER)
                .withWaterTravelFlag(true);
        var result = BedrockSimulation.move(
                previous,
                BedrockInputFrame.idle(47407),
                context,
                BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
                false,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP);
        assertTrue(result.selectedWaterTravel());
        // The lifted box still reaches the water cell the player swims in, so the client does not climb out.
        assertFalse(result.predictedState().collisionFlags().liquidClimbOut());
        assertEquals(result.predictedState(), BedrockForwardTick.finish(result, result.predictedState()));
    }

    // A scale change grows the swimming box into the pool wall it was pressed against. The move then passes through
    // the overlapped wall without a horizontal collision and the client does not climb out on that tick, although the
    // lifted box is free; the same move against the wall from the box that only touches it climbs out.
    @Test
    public void climbOutNeedsTheMovesOwnHorizontalCollision() {
        var grown = poolWallMove(new PlayerDimensionsState(0.78F, 2.34F));
        assertFalse(grown.predictedState().collisionFlags().horizontalCollision());
        assertEquals(0.0196D, grown.predictedPosition().z() - 0.76D, 1.0E-6D);
        assertFalse(grown.predictedState().collisionFlags().liquidClimbOut());

        var touching = poolWallMove(new PlayerDimensionsState(0.48F, 1.44F));
        assertTrue(touching.predictedState().collisionFlags().horizontalCollision());
        assertTrue(touching.predictedState().collisionFlags().liquidClimbOut());
        assertEquals(
                BedrockLiquidClimbOutMovement.CLIMB_OUT_VELOCITY_Y,
                touching.predictedState().velocity().y(),
                0.0D);
    }

    private static BedrockMovementResult poolWallMove(PlayerDimensionsState dimensions) {
        var world = new BlockCollisionWorld(List.of(
                stone(0, -1, 0),
                stone(0, 0, 1),
                PlacedBlockCollision.manual(
                        new BlockPosition(0, 0, 0),
                        "minecraft:water[level=0]",
                        "minecraft:water",
                        Map.of("liquid_depth", 0),
                        List.of())));
        var context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.WATER, FluidState.NONE, world),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                dimensions);
        var previous = BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(0.5D, 0.28589630126953125D, 0.76D),
                        new Vec3d(0.0D, 0.182823D, 0.0196D),
                        BedrockInputFrame.idle(21437),
                        BedrockCollisionFlags.AIR,
                        Medium.WATER)
                .withWaterTravelFlag(true)
                .withPlayerDimensions(dimensions, true);
        return BedrockSimulation.move(
                previous,
                BedrockInputFrame.idle(21438),
                context,
                BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
                false,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP);
    }

    private static PlacedBlockCollision stone(int x, int y, int z) {
        return PlacedBlockCollision.manual(
                new BlockPosition(x, y, z),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(x, y, z, x + 1, y + 1, z + 1)));
    }
}
