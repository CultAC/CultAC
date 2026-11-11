package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.api.BedrockBlockShapeContext;
import ac.cult.cultac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
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
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import ac.cult.cultac.utils.collisions.BedrockClientBlockShapeMappings;
import java.util.List;
import org.junit.Test;

public final class BedrockComposterLandingTest {
    @Test
    public void aFallOverAComposterKeepsTheHeightTheClientKept() {
        // Captured fall over a composter at level 7: from feet 121.1213, moving 0.10545 forward and 0.444826 down
        // inside its walls, the client stays at 121.1213, moves its full 0.10545 and reports (0.095964, -0.0784).
        // A composter collides as four walls around a 15/16 high floor, not as a full block.
        var builder = new BedrockCollisionWorldBuilder(
                BedrockClientBlockShapeMappings.catalog(), BedrockBlockShapeContext.NONE);
        var registry = DataTables.defaults().registry();
        int composter = registry.with(registry.block("minecraft:composter").defaultState(), "level", "7");
        var block = builder.block(new BlockPosition(17, 120, 2), composter, BedrockCollisionShapeQuery.NONE);
        assertEquals(5, block.collisionBoxes().size());
        assertEquals(120.9375D, block.collisionBoxes().getFirst().maxY(), 0.0D);
        BedrockMovementContext context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of(block))),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
        BedrockMovementState falling = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(17.4855D, 121.121295D, 2.5D),
                new Vec3d(0.105455D, -0.444826D, 0.0D),
                BedrockInputFrame.idle(1L),
                BedrockCollisionFlags.AIR);
        BedrockMovementResult result = BedrockSimulation.move(
                falling,
                BedrockInputFrame.idle(2L),
                context,
                BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP);
        assertEquals(17.59095D, result.predictedPosition().x(), 1.0E-5D);
        assertEquals(121.121295D, result.predictedPosition().y(), 1.0E-5D);
        assertEquals(0.095964D, result.predictedVelocity().x(), 1.0E-5D);
        assertEquals(-0.0784D, result.predictedVelocity().y(), 1.0E-6D);
    }

    @Test
    public void cauldronsAndBrewingStandsTakeTheirOwnBoxes() {
        var builder = new BedrockCollisionWorldBuilder(
                BedrockClientBlockShapeMappings.catalog(), BedrockBlockShapeContext.NONE);
        var registry = DataTables.defaults().registry();
        for (String cauldron : List.of(
                "minecraft:cauldron", "minecraft:water_cauldron", "minecraft:lava_cauldron",
                "minecraft:powder_snow_cauldron")) {
            var boxes = builder.block(
                            new BlockPosition(0, 0, 0),
                            registry.block(cauldron).defaultState(),
                            BedrockCollisionShapeQuery.NONE)
                    .collisionBoxes();
            assertEquals(cauldron, 5, boxes.size());
            assertEquals(cauldron, 0.3125D, boxes.getFirst().maxY(), 0.0D);
        }
        var stand = builder.block(
                        new BlockPosition(0, 0, 0),
                        registry.block("minecraft:brewing_stand").defaultState(),
                        BedrockCollisionShapeQuery.NONE)
                .collisionBoxes();
        assertEquals(2, stand.size());
        assertEquals(0.875D, stand.get(0).maxY(), 0.0D);
        assertEquals(0.4375D, stand.get(0).minX(), 0.0D);
        assertEquals(0.125D, stand.get(1).maxY(), 0.0D);
        assertEquals(1.0D, stand.get(1).maxX(), 0.0D);
    }

    @Test
    public void hoppersCollideAsBowlFunnelAndTheSpoutTheyFace() {
        var builder = new BedrockCollisionWorldBuilder(
                BedrockClientBlockShapeMappings.catalog(), BedrockBlockShapeContext.NONE);
        var registry = DataTables.defaults().registry();
        int hopper = registry.block("minecraft:hopper").defaultState();
        var down = builder.block(new BlockPosition(0, 0, 0), registry.with(hopper, "facing", "down"),
                        BedrockCollisionShapeQuery.NONE)
                .collisionBoxes();
        var east = builder.block(new BlockPosition(0, 0, 0), registry.with(hopper, "facing", "east"),
                        BedrockCollisionShapeQuery.NONE)
                .collisionBoxes();

        assertEquals(7, down.size());
        assertEquals(0.6875D, down.getFirst().maxY(), 0.0D);
        assertEquals(0.0D, down.getLast().minY(), 0.0D);
        assertEquals(0.25D, down.getLast().maxY(), 0.0D);
        assertEquals(0.75D, east.getLast().minX(), 0.0D);
        assertEquals(1.0D, east.getLast().maxX(), 0.0D);
        assertEquals(0.25D, east.getLast().minY(), 0.0D);
    }
}
