package ac.cult.cultac.bedrock.prediction.simulation.frame;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.api.BedrockFluidMovementSource;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class BedrockFluidStateResolverTest {
    @Test
    public void blockWorldFlowingWaterCreatesCurrentState() {
        BedrockMovementContext context = contextWithWorld(new BlockCollisionWorld(List.of(PlacedBlockCollision.manual(
                new BlockPosition(0, 0, 0),
                "minecraft:water[level=1]",
                "minecraft:flowing_water",
                Map.of(
                        "liquid_depth", 1,
                        "flow_x", 1.0D,
                        "flow_y", 0.0D,
                        "flow_z", 0.0D),
                List.of()))));

        BedrockMovementContext resolved =
                BedrockFluidStateResolver.withFluidStateFromBlockWorld(context, new Vec3d(0.5D, 0.0D, 0.5D));

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
                        List.of()))));

        BedrockMovementContext resolved =
                BedrockFluidStateResolver.withFluidStateFromBlockWorld(context, new Vec3d(0.5D, 0.0D, 0.5D));

        assertEquals(Medium.WATER, resolved.worldState().medium());
        assertTrue(resolved.worldState().fluidState().bubbleColumnUp());
        assertFalse(resolved.worldState().fluidState().bubbleColumnDown());
    }

    @Test
    public void bubbleColumnTouchesTheBoxShrunkByAThousandthOnEverySide() {
        BlockCollisionWorld column = new BlockCollisionWorld(List.of(PlacedBlockCollision.manual(
                new BlockPosition(1, 0, 0),
                "minecraft:bubble_column[drag=false]",
                "minecraft:bubble_column",
                Map.of("drag", false),
                List.of())));

        // A box 0.0005 into the column misses it; 0.0015 into it is pushed.
        assertFalse(bubbleLayersAt(column, new Vec3d(0.7005D, 0.0D, 0.5D)));
        assertTrue(bubbleLayersAt(column, new Vec3d(0.7015D, 0.0D, 0.5D)));
        assertFalse(bubbleLayersAt(column, new Vec3d(1.5D, 0.9995D, 0.5D)));
        assertTrue(bubbleLayersAt(column, new Vec3d(1.5D, 0.9985D, 0.5D)));
    }

    private static boolean bubbleLayersAt(BlockCollisionWorld world, Vec3d feet) {
        return !BedrockFluidStateResolver.withFluidStateFromBlockWorld(contextWithWorld(world), feet)
                .worldState()
                .fluidState()
                .bubbleColumnState()
                .layers()
                .isEmpty();
    }

    @Test
    public void lavaContactDoesNotIncludeSurfaceBoundaryQuantization() {
        BedrockMovementContext context = contextWithWorld(new BlockCollisionWorld(List.of(PlacedBlockCollision.manual(
                new BlockPosition(0, 0, 0),
                "minecraft:lava[level=0]",
                "minecraft:lava",
                Map.of("liquid_depth", 0),
                List.of()))));

        BedrockMovementContext resolved =
                BedrockFluidStateResolver.withFluidStateFromBlockWorld(context, new Vec3d(0.5D, 0.600006D, 0.5D));

        assertEquals(Medium.AIR, resolved.worldState().medium());
    }

    @Test
    public void lavaContactDoesNotExtendPastBoundaryEpsilon() {
        BedrockMovementContext context = contextWithWorld(new BlockCollisionWorld(List.of(PlacedBlockCollision.manual(
                new BlockPosition(0, 0, 0),
                "minecraft:lava[level=0]",
                "minecraft:lava",
                Map.of("liquid_depth", 0),
                List.of()))));

        BedrockMovementContext resolved =
                BedrockFluidStateResolver.withFluidStateFromBlockWorld(context, new Vec3d(0.5D, 0.60002D, 0.5D));

        assertEquals(Medium.AIR, resolved.worldState().medium());
    }

    @Test
    public void lavaSwimUpUsesStrictLiquidAabbContact() {
        BedrockMovementContext context = contextWithWorld(new BlockCollisionWorld(List.of(PlacedBlockCollision.manual(
                new BlockPosition(0, 0, 0),
                "minecraft:lava[level=0]",
                "minecraft:lava",
                Map.of("liquid_depth", 0),
                List.of()))));

        assertTrue(BedrockLiquidSensing.lavaSwimUpApplies(
                context, new Vec3d(0.5D, 0.560006D, 0.5D), PlayerDimensionsState.DEFAULT));
        assertFalse(BedrockLiquidSensing.lavaSwimUpApplies(
                context, new Vec3d(0.5D, 0.600006D, 0.5D), PlayerDimensionsState.DEFAULT));
    }

    @Test
    public void headWaterPointUsesFlowingWaterSurfaceHeight() {
        BlockCollisionWorld water = new BlockCollisionWorld(
                List.of(waterBlock(new BlockPosition(297, 83, -100), "minecraft:flowing_water", 3)));

        assertTrue(BedrockLiquidGeometry.liquidPointInBlock(water, 297.5D, 83.6D, -99.5D, BedrockLiquidKind.WATER));
        assertFalse(BedrockLiquidGeometry.liquidPointInBlock(
                water, 297.5D, 83.66666666666667D, -99.5D, BedrockLiquidKind.WATER));
        assertFalse(BedrockLiquidGeometry.liquidPointInBlock(water, 297.5D, 83.9D, -99.5D, BedrockLiquidKind.WATER));
    }

    @Test
    public void flaggedSwimmingHeightIsAboveTheFlowingWaterSurface() {
        BedrockMovementContext context = contextWithWorld(new BlockCollisionWorld(
                List.of(waterBlock(new BlockPosition(297, 83, -100), "minecraft:flowing_water", 3))));

        assertFalse(BedrockUnderwaterSensing.update(
                        ac.cult.cultac.bedrock.prediction.state.BedrockCameraWaterState.INITIAL,
                        context,
                        new Vec3d(297.8619079589844D, 83.50765991210938D, -99.51327514648438D),
                        new PlayerDimensionsState(0.6D, 0.6D))
                .headInWater());
    }

    @Test
    public void cameraHistoryIsIndependentOfExplicitCollisionHeight() {
        BedrockMovementContext context = contextWithWorld(
                new BlockCollisionWorld(List.of(waterBlock(new BlockPosition(0, 64, 0), "minecraft:water", 0))));
        var feet = new Vec3d(0.5D, 64.0D, 0.5D);
        var standingCamera = ac.cult.cultac.bedrock.prediction.state.BedrockCameraWaterState.INITIAL;
        assertFalse(
                BedrockUnderwaterSensing.update(standingCamera, context, feet, new PlayerDimensionsState(0.6F, 0.6F))
                        .headInWater());
        var recoveringCamera =
                new ac.cult.cultac.bedrock.prediction.state.BedrockCameraWaterState(0.610005F, 1.22001F, false, false);
        var sensed =
                BedrockUnderwaterSensing.update(recoveringCamera, context, feet, new PlayerDimensionsState(0.6F, 1.8F));
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

        assertTrue(BedrockLiquidGeometry.liquidPointInBlock(water, 0.5D, 0.999D, 0.5D, BedrockLiquidKind.WATER));
        assertTrue(BedrockLiquidGeometry.liquidPointInBlock(water, 1.5D, 0.999D, 0.5D, BedrockLiquidKind.WATER));
    }

    @Test
    public void sourceOnlyContactIgnoresFlowTowardLowerLiquidBesideAir() {
        BedrockMovementContext resolved = BedrockFluidStateResolver.withFluidStateFromBlockWorld(
                contextWithWorld(
                        sourceEdgeWorld(waterBlock(new BlockPosition(1, -1, 0), "minecraft:flowing_water", 1))),
                new Vec3d(0.7D, 0.3D, 0.67D));

        assertEquals(Medium.WATER, resolved.worldState().medium());
        assertFalse(resolved.worldState().fluidState().current());
        assertFalse(
                BedrockFluidMovementSourceResolver.fromContext(resolved, 0.0D).active());
    }

    @Test
    public void sourceOnlyContactAppliesFlowFromAdjacentFlowingBlock() {
        BedrockMovementContext resolved = BedrockFluidStateResolver.withFluidStateFromBlockWorld(
                contextWithWorld(sourceEdgeWorld(waterBlock(new BlockPosition(1, 0, 0), "minecraft:flowing_water", 1))),
                new Vec3d(0.7D, 0.3D, 0.67D));

        assertTrue(resolved.worldState().fluidState().current());
        BedrockFluidMovementSource source = BedrockFluidMovementSourceResolver.fromContext(resolved, 0.0D);
        assertTrue(source.active());
        assertEquals(0.014D, source.appliedDelta().x(), 0.0D);
        assertEquals(0.0D, source.appliedDelta().z(), 0.0D);
    }

    @Test
    public void bubbleColumnSourceNeighborPushesFlowingWaterAwayFromIt() {
        // Level-1 water fed by a bubble column to its north, a piston to its south and pressure plates on stone east
        // and west: the column's source water is the only neighbour that shapes the current, 0.014 along +Z.
        BlockPosition column = new BlockPosition(0, 0, -1);
        BedrockMovementContext context = contextWithWorld(new BlockCollisionWorld(List.of(
                waterBlock(new BlockPosition(0, 0, 0), "minecraft:flowing_water", 1),
                PlacedBlockCollision.manual(
                        column,
                        "minecraft:bubble_column[drag=false]",
                        "minecraft:bubble_column",
                        Map.of("drag_down", false),
                        List.of()),
                PlacedBlockCollision.manual(
                        column,
                        "minecraft:bubble_column[drag=false]",
                        "minecraft:water",
                        Map.of("liquid_depth", 0),
                        List.of()),
                fullBlock(new BlockPosition(0, -1, -1), "minecraft:soul_sand"),
                fullBlock(new BlockPosition(0, 0, 1), "minecraft:piston"),
                PlacedBlockCollision.manual(
                        new BlockPosition(1, 0, 0),
                        "minecraft:stone_pressure_plate",
                        "minecraft:stone_pressure_plate",
                        List.of()),
                PlacedBlockCollision.manual(
                        new BlockPosition(-1, 0, 0),
                        "minecraft:stone_pressure_plate",
                        "minecraft:stone_pressure_plate",
                        List.of()),
                fullBlock(new BlockPosition(1, -1, 0), "minecraft:stone"),
                fullBlock(new BlockPosition(0, -1, 0), "minecraft:stone"),
                fullBlock(new BlockPosition(-1, -1, 0), "minecraft:stone"))));

        BedrockMovementContext resolved = BedrockFluidStateResolver.withFluidStateFromBlockWorld(
                context, new Vec3d(-0.29052734375D, 0.57294464111328D, 0.34054183959961D));

        assertEquals(Medium.WATER, resolved.worldState().medium());
        assertTrue(resolved.worldState().fluidState().currentPositiveZ());
        BedrockFluidMovementSource source = BedrockFluidMovementSourceResolver.fromContext(resolved, 0.57294464111328D);
        assertEquals(new Vec3d(0.0D, 0.0D, 0.014D), source.appliedDelta());
    }

    @Test
    public void waterloggedStairBackFaceKeepsItsWaterOutOfTheFlow() {
        assertCurrent(new Vec3d(0.014D, 0.0D, 0.0D), channelCurrent(stair(new BlockPosition(0, 0, 1), "north")));
    }

    @Test
    public void waterloggedStairFrontFaceLetsItsWaterShapeTheFlow() {
        assertCurrent(divertedCurrent(), channelCurrent(stair(new BlockPosition(0, 0, 1), "south")));
    }

    @Test
    public void waterloggedStairSideClosesWhereTheStairInFrontMakesAnInnerCorner() {
        assertCurrent(
                new Vec3d(0.014D, 0.0D, 0.0D),
                channelCurrent(
                        stair(new BlockPosition(0, 0, 1), "east"),
                        stair(new BlockPosition(-1, 0, 1), "north")));
    }

    @Test
    public void waterloggedStairSideStaysOpenWithoutAnInnerCorner() {
        assertCurrent(
                divertedCurrent(),
                channelCurrent(
                        stair(new BlockPosition(0, 0, 1), "east"),
                        fullBlock(new BlockPosition(-1, 0, 1), "minecraft:stone")));
    }

    @Test
    public void anyStairInFrontMakesTheInnerCorner() {
        assertCurrent(
                new Vec3d(0.014D, 0.0D, 0.0D),
                channelCurrent(
                        stair(new BlockPosition(0, 0, 1), "east"),
                        facingBlock(new BlockPosition(-1, 0, 1), "minecraft:stone_brick_stairs", "north", 0.5D)));
    }

    @Test
    public void eastFacingShelfClosesItsNorthFace() {
        assertCurrent(new Vec3d(0.014D, 0.0D, 0.0D), channelCurrent(shelf(new BlockPosition(0, 0, 1), "east")));
    }

    @Test
    public void northFacingShelfLeavesItsNorthFaceOpen() {
        assertCurrent(divertedCurrent(), channelCurrent(shelf(new BlockPosition(0, 0, 1), "north")));
    }

    @Test
    public void southFacingShelfLeavesEverySideOpen() {
        assertCurrent(divertedCurrent(), channelCurrent(shelf(new BlockPosition(0, 0, 1), "south")));
    }

    /**
     * Level-1 water at the origin fed by a source to its west, spilling into level-2 water to its east, stone to its
     * north and the given blocks to its south: the current a player standing only in the level-1 water feels.
     */
    private static Vec3d channelCurrent(PlacedBlockCollision... southBlocks) {
        List<PlacedBlockCollision> blocks = new ArrayList<>(List.of(
                waterBlock(new BlockPosition(-1, 0, 0), "minecraft:water", 0),
                waterBlock(new BlockPosition(0, 0, 0), "minecraft:flowing_water", 1),
                waterBlock(new BlockPosition(1, 0, 0), "minecraft:flowing_water", 2),
                fullBlock(new BlockPosition(0, 0, -1), "minecraft:stone"),
                fullBlock(new BlockPosition(-1, -1, 0), "minecraft:stone"),
                fullBlock(new BlockPosition(0, -1, 0), "minecraft:stone"),
                fullBlock(new BlockPosition(1, -1, 0), "minecraft:stone")));
        for (PlacedBlockCollision block : southBlocks) {
            blocks.add(block);
            if (block.javaStateProperties().waterlogged()) {
                blocks.add(waterBlock(block.position(), "minecraft:water", 0));
            }
        }
        BedrockMovementContext resolved = BedrockFluidStateResolver.withFluidStateFromBlockWorld(
                contextWithWorld(new BlockCollisionWorld(blocks)), new Vec3d(0.5D, 0.0D, 0.5D));
        return BedrockFluidMovementSourceResolver.fromContext(resolved, 0.0D).appliedDelta();
    }

    private static void assertCurrent(Vec3d expected, Vec3d actual) {
        assertEquals(expected.x(), actual.x(), 1.0E-12D);
        assertEquals(expected.y(), actual.y(), 1.0E-12D);
        assertEquals(expected.z(), actual.z(), 1.0E-12D);
    }

    /** The channel's current once the stair's source water also pushes away from it: (2, 0, -1) normalized. */
    private static Vec3d divertedCurrent() {
        double length = Math.sqrt(5.0D);
        return new Vec3d(2.0D / length * 0.014D, 0.0D, -1.0D / length * 0.014D);
    }

    private static PlacedBlockCollision stair(BlockPosition position, String facing) {
        return facingBlock(position, "minecraft:oak_stairs", facing, 0.5D);
    }

    private static PlacedBlockCollision shelf(BlockPosition position, String facing) {
        return facingBlock(position, "minecraft:oak_shelf", facing, 1.0D);
    }

    /** A waterlogged block with a horizontal facing, collided with as a box of the given height. */
    private static PlacedBlockCollision facingBlock(
            BlockPosition position, String identifier, String facing, double height) {
        return PlacedBlockCollision.manual(
                position,
                identifier + "[facing=" + facing + ",waterlogged=true]",
                identifier,
                List.of(new WorldCollisionBox(
                        position.x(),
                        position.y(),
                        position.z(),
                        position.x() + 1,
                        position.y() + height,
                        position.z() + 1)));
    }

    private static PlacedBlockCollision fullBlock(BlockPosition position, String identifier) {
        return PlacedBlockCollision.manual(
                position,
                identifier,
                identifier,
                List.of(new WorldCollisionBox(
                        position.x(),
                        position.y(),
                        position.z(),
                        position.x() + 1,
                        position.y() + 1,
                        position.z() + 1)));
    }

    private static BlockCollisionWorld sourceEdgeWorld(PlacedBlockCollision flowingBlock) {
        return new BlockCollisionWorld(List.of(
                waterBlock(new BlockPosition(0, 0, 0), "minecraft:water", 0),
                waterBlock(new BlockPosition(-1, 0, 0), "minecraft:water", 0),
                waterBlock(new BlockPosition(0, 0, 1), "minecraft:water", 0),
                waterBlock(new BlockPosition(0, 0, -1), "minecraft:water", 0),
                flowingBlock));
    }

    private static PlacedBlockCollision waterBlock(BlockPosition position, String identifier, int liquidDepth) {
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
                new WorldContactState(Medium.AIR, FluidState.NONE, blockWorld),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
    }
}
