package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.api.BedrockFluidMovementSource;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BedrockFluidStateResolverTest {
    @Test
    public void blockWorldFlowingWaterCreatesCurrentState() {
        BedrockMovementContext context = contextWithWorld(new BlockCollisionWorld(List.of(
                PlacedBlockCollision.manual(
                        new BlockPosition(0, 0, 0),
                        "minecraft:water[level=1]",
                        "minecraft:flowing_water",
                        Map.of(
                                "liquid_depth", 1,
                                "flow_x", 1.0D,
                                "flow_y", 0.0D,
                                "flow_z", 0.0D
                        ),
                        List.of())
        )));

        BedrockMovementContext resolved = BedrockFluidStateResolver.withFluidStateFromBlockWorld(
                context,
                new Vec3d(0.5D, 0.0D, 0.5D));

        assertEquals(Medium.WATER, resolved.worldState().medium());
        assertTrue(resolved.worldState().fluidState().current());
        BedrockFluidMovementSource source = BedrockFluidMovementSourceResolver.fromContext(resolved, 0.0D);
        assertTrue(source.active());
        assertEquals(0.014D, source.appliedDelta().x(), 0.0D);
        assertEquals(0.0D, source.appliedDelta().y(), 0.0D);
        assertEquals(0.0D, source.appliedDelta().z(), 0.0D);
    }

    @Test
    public void bubbleColumnBlockOwnsDragWhenWaterFluidEntrySharesItsPosition() {
        BlockPosition position = new BlockPosition(0, 0, 0);
        BedrockMovementContext context = contextWithWorld(new BlockCollisionWorld(List.of(
                PlacedBlockCollision.manual(
                        position,
                        "minecraft:bubble_column[drag=false]",
                        "minecraft:bubble_column",
                        Map.of("drag", false),
                        List.of()),
                PlacedBlockCollision.manual(
                        position,
                        "minecraft:bubble_column[drag=false]",
                        "minecraft:water",
                        Map.of("liquid_depth", 0),
                        List.of())
        )));

        BedrockMovementContext resolved = BedrockFluidStateResolver.withFluidStateFromBlockWorld(
                context,
                new Vec3d(0.5D, 0.0D, 0.5D));

        assertEquals(Medium.WATER, resolved.worldState().medium());
        assertTrue(resolved.worldState().fluidState().bubbleColumnUp());
        assertFalse(resolved.worldState().fluidState().bubbleColumnDown());
    }

    @Test
    public void lavaContactDoesNotIncludeSurfaceBoundaryQuantization() {
        BedrockMovementContext context = contextWithWorld(new BlockCollisionWorld(List.of(
                PlacedBlockCollision.manual(
                        new BlockPosition(0, 0, 0),
                        "minecraft:lava[level=0]",
                        "minecraft:lava",
                        Map.of("liquid_depth", 0),
                        List.of())
        )));

        BedrockMovementContext resolved = BedrockFluidStateResolver.withFluidStateFromBlockWorld(
                context,
                new Vec3d(0.5D, 0.600006D, 0.5D));

        assertEquals(Medium.AIR, resolved.worldState().medium());
    }

    @Test
    public void lavaContactDoesNotExtendPastBoundaryEpsilon() {
        BedrockMovementContext context = contextWithWorld(new BlockCollisionWorld(List.of(
                PlacedBlockCollision.manual(
                        new BlockPosition(0, 0, 0),
                        "minecraft:lava[level=0]",
                        "minecraft:lava",
                        Map.of("liquid_depth", 0),
                        List.of())
        )));

        BedrockMovementContext resolved = BedrockFluidStateResolver.withFluidStateFromBlockWorld(
                context,
                new Vec3d(0.5D, 0.60002D, 0.5D));

        assertEquals(Medium.AIR, resolved.worldState().medium());
    }

    @Test
    public void lavaSwimUpUsesStrictLiquidAabbContact() {
        BedrockMovementContext context = contextWithWorld(new BlockCollisionWorld(List.of(
                PlacedBlockCollision.manual(
                        new BlockPosition(0, 0, 0),
                        "minecraft:lava[level=0]",
                        "minecraft:lava",
                        Map.of("liquid_depth", 0),
                        List.of())
        )));

        assertTrue(BedrockLiquidSensing.lavaSwimUpApplies(
                context,
                new Vec3d(0.5D, 0.560006D, 0.5D),
                PlayerDimensionsState.DEFAULT));
        assertFalse(BedrockLiquidSensing.lavaSwimUpApplies(
                context,
                new Vec3d(0.5D, 0.600006D, 0.5D),
                PlayerDimensionsState.DEFAULT));
    }

    @Test
    public void headWaterPointUsesFlowingWaterSurfaceHeight() {
        BlockCollisionWorld water = new BlockCollisionWorld(List.of(
                waterBlock(new BlockPosition(297, 83, -100), "minecraft:flowing_water", 3)));

        assertTrue(BedrockLiquidGeometry.liquidPointInBlock(
                water, 297.5D, 83.6D, -99.5D, BedrockLiquidKind.WATER));
        assertFalse(BedrockLiquidGeometry.liquidPointInBlock(
                water, 297.5D, 83.66666666666667D, -99.5D, BedrockLiquidKind.WATER));
        assertFalse(BedrockLiquidGeometry.liquidPointInBlock(
                water, 297.5D, 83.9D, -99.5D, BedrockLiquidKind.WATER));
    }

    @Test
    public void flaggedSwimmingHeightIsAboveTheFlowingWaterSurface() {
        BedrockMovementContext context = contextWithWorld(new BlockCollisionWorld(List.of(
                waterBlock(new BlockPosition(297, 83, -100), "minecraft:flowing_water", 3))));

        assertFalse(BedrockUnderwaterSensing.update(
                ac.cult.cultac.bedrock.prediction.state.BedrockCameraWaterState.INITIAL, context,
                new Vec3d(297.8619079589844D, 83.50765991210938D, -99.51327514648438D),
                new PlayerDimensionsState(0.6D, 0.6D)).headInWater());
    }

    @Test
    public void cameraHistoryIsIndependentOfExplicitCollisionHeight() {
        BedrockMovementContext context = contextWithWorld(new BlockCollisionWorld(List.of(
                waterBlock(new BlockPosition(0, 64, 0), "minecraft:water", 0))));
        var feet = new Vec3d(0.5D, 64.0D, 0.5D);
        var standingCamera = ac.cult.cultac.bedrock.prediction.state.BedrockCameraWaterState.INITIAL;
        assertFalse(BedrockUnderwaterSensing.update(standingCamera, context, feet,
                new PlayerDimensionsState(0.6F, 0.6F)).headInWater());
        var recoveringCamera = new ac.cult.cultac.bedrock.prediction.state.BedrockCameraWaterState(
                0.610005F, 1.22001F, false, false);
        var sensed = BedrockUnderwaterSensing.update(recoveringCamera, context, feet,
                new PlayerDimensionsState(0.6F, 1.8F));
        assertTrue(sensed.headInWater());
        assertEquals(recoveringCamera.previousOffset(), sensed.previousOffset(), 0.0F);
        assertEquals(recoveringCamera.currentOffset(), sensed.currentOffset(), 0.0F);
    }

    @Test
    public void sourceWaterAndBubbleColumnsRemainFullHeightLiquids() {
        BlockCollisionWorld water = new BlockCollisionWorld(List.of(
                waterBlock(new BlockPosition(0, 0, 0), "minecraft:water", 0),
                PlacedBlockCollision.manual(
                        new BlockPosition(1, 0, 0),
                        "minecraft:bubble_column[drag=false]",
                        "minecraft:bubble_column",
                        Map.of("drag", false),
                        List.of())));

        assertTrue(BedrockLiquidGeometry.liquidPointInBlock(
                water, 0.5D, 0.999D, 0.5D, BedrockLiquidKind.WATER));
        assertTrue(BedrockLiquidGeometry.liquidPointInBlock(
                water, 1.5D, 0.999D, 0.5D, BedrockLiquidKind.WATER));
    }

    private static PlacedBlockCollision waterBlock(
            BlockPosition position,
            String identifier,
            int liquidDepth) {
        return PlacedBlockCollision.manual(
                position,
                identifier + "[level=" + liquidDepth + "]",
                identifier,
                Map.of("liquid_depth", liquidDepth),
                List.of());
    }

    private static BedrockMovementContext contextWithWorld(BlockCollisionWorld blockWorld) {
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(
                        Medium.AIR,
                FluidState.NONE,
                blockWorld),
            EquipmentState.NONE,
            EntityContactState.NONE,
            MovementModifierState.NONE,
            PlayerDimensionsState.DEFAULT);
    }
}
