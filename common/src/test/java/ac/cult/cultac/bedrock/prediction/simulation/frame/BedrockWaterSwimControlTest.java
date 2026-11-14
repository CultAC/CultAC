package ac.cult.cultac.bedrock.prediction.simulation.frame;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockCameraWaterState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class BedrockWaterSwimControlTest {
    private static final float LOOK_UP = -11.831924F;
    private static final float LOOK_DOWN = 30.0F;
    private static final Vec3d RISING = new Vec3d(0.0D, 0.16D, 0.0D);
    private static final BedrockCameraWaterState SWIM_CAMERA =
            new BedrockCameraWaterState(1.22001F, 1.22001F, true, true);

    @Test
    public void aBlockHoldingWaterIsNotALiquidAtTheHead() {
        BlockCollisionWorld world = new BlockCollisionWorld(List.of(
                block(new BlockPosition(0, 0, 0), "minecraft:ladder"),
                water(new BlockPosition(0, 0, 0), "minecraft:water", 0),
                block(new BlockPosition(1, 0, 0), "minecraft:kelp"),
                water(new BlockPosition(1, 0, 0), "minecraft:water", 0),
                block(new BlockPosition(2, 0, 0), "minecraft:seagrass"),
                water(new BlockPosition(2, 0, 0), "minecraft:water", 0),
                water(new BlockPosition(3, 0, 0), "minecraft:water", 0),
                water(new BlockPosition(4, 0, 0), "minecraft:flowing_water", 7),
                water(new BlockPosition(5, 0, 0), "minecraft:lava", 0),
                block(new BlockPosition(6, 0, 0), "minecraft:bubble_column"),
                water(new BlockPosition(6, 0, 0), "minecraft:water", 0)));

        assertEquals(BedrockHeadBlock.OTHER, BedrockHeadBlock.at(world, 0.5D, 0.4D, 0.5D));
        assertEquals(BedrockHeadBlock.OTHER, BedrockHeadBlock.at(world, 1.5D, 0.4D, 0.5D));
        assertEquals(BedrockHeadBlock.OTHER, BedrockHeadBlock.at(world, 2.5D, 0.4D, 0.5D));
        assertEquals(BedrockHeadBlock.LIQUID, BedrockHeadBlock.at(world, 3.5D, 0.4D, 0.5D));
        // Above the surface of shallow flowing water the block is still a liquid.
        assertEquals(BedrockHeadBlock.LIQUID, BedrockHeadBlock.at(world, 4.5D, 0.9D, 0.5D));
        assertEquals(BedrockHeadBlock.LIQUID, BedrockHeadBlock.at(world, 5.5D, 0.4D, 0.5D));
        assertEquals(BedrockHeadBlock.LIQUID, BedrockHeadBlock.at(world, 6.5D, 0.4D, 0.5D));
        assertEquals(BedrockHeadBlock.AIR, BedrockHeadBlock.at(world, 7.5D, 0.4D, 0.5D));
        assertEquals(BedrockHeadBlock.AIR, BedrockHeadBlock.at(world, 3.5D, 1.0D, 0.5D));
    }

    @Test
    public void aSwimmerInAWaterloggedLadderHasTheHeadInWaterAndNoLiquidBlockThere() {
        BedrockMovementContext context = context(
                new BlockCollisionWorld(List.of(
                        block(new BlockPosition(0, 0, 0), "minecraft:ladder"),
                        water(new BlockPosition(0, 0, 0), "minecraft:water", 0),
                        block(new BlockPosition(0, 1, 0), "minecraft:ladder"),
                        water(new BlockPosition(0, 1, 0), "minecraft:water", 0))),
                false);
        Vec3d feet = new Vec3d(0.388D, 0.99998474121094D, 0.5125D);

        assertTrue(BedrockUnderwaterSensing.update(SWIM_CAMERA, context, feet, new PlayerDimensionsState(0.6F, 0.6F))
                .headInWater());
        assertEquals(BedrockHeadBlock.OTHER, BedrockUnderwaterSensing.headBlock(SWIM_CAMERA, context, feet));
    }

    @Test
    public void anUpwardLookLiftsASwimmerOnlyWithALiquidBlockAtTheHead() {
        double lookY = BedrockMath.lookDirectionY(LOOK_UP);

        assertEquals(0.16D + (lookY - 0.16D) * 0.06D, swim(LOOK_UP, false, false, BedrockHeadBlock.LIQUID), 0.0D);
        assertEquals(0.0D, swim(LOOK_UP, false, false, BedrockHeadBlock.OTHER), 0.0D);
        assertEquals(0.0D, swim(LOOK_UP, false, false, BedrockHeadBlock.AIR), 0.0D);
    }

    @Test
    public void aDownwardLookSinksASwimmerWhateverTheHeadIsIn() {
        double lookY = BedrockMath.lookDirectionY(LOOK_DOWN);
        double sinking = 0.16D + (lookY - 0.16D) * 0.085D;

        assertEquals(sinking, swim(LOOK_DOWN, false, false, BedrockHeadBlock.LIQUID), 0.0D);
        assertEquals(sinking, swim(LOOK_DOWN, false, false, BedrockHeadBlock.OTHER), 0.0D);
        assertEquals(sinking, swim(LOOK_DOWN, false, false, BedrockHeadBlock.AIR), 0.0D);
    }

    @Test
    public void aFlyingSwimmerLookingUpStopsOnlyWithTheHeadInAirAndNoDescent() {
        double lookY = BedrockMath.lookDirectionY(LOOK_UP);
        double lifted = 0.16D + (lookY - 0.16D) * 1.3D;

        assertEquals(lifted, swim(LOOK_UP, true, false, BedrockHeadBlock.LIQUID), 0.0D);
        assertEquals(lifted, swim(LOOK_UP, true, false, BedrockHeadBlock.OTHER), 0.0D);
        assertEquals(0.0D, swim(LOOK_UP, true, false, BedrockHeadBlock.AIR), 0.0D);
        assertEquals(lifted, swim(LOOK_UP, true, true, BedrockHeadBlock.AIR), 0.0D);
    }

    private static double swim(float pitch, boolean flying, boolean descending, BedrockHeadBlock headBlock) {
        BedrockInputFrame frame = new BedrockInputFrame(
                1L, 0.0F, pitch, false, false, false, descending ? Set.of("WANT_DOWN") : Set.of());
        return BedrockWaterSwimControl.lookAdjustedVelocity(
                        RISING, frame, frame.intent(), context(BlockCollisionWorld.EMPTY, flying), headBlock)
                .y();
    }

    private static PlacedBlockCollision block(BlockPosition position, String identifier) {
        return PlacedBlockCollision.manual(position, identifier, identifier, Map.of(), List.of());
    }

    private static PlacedBlockCollision water(BlockPosition position, String identifier, int liquidDepth) {
        return PlacedBlockCollision.manual(
                position,
                identifier + "[level=" + liquidDepth + "]",
                identifier,
                Map.of("liquid_depth", liquidDepth),
                List.of());
    }

    private static BedrockMovementContext context(BlockCollisionWorld blockWorld, boolean flying) {
        MovementModifierState modifiers = flying
                ? new MovementModifierState(false, true, true, false, 0.05D, false, false, false, false, 0.35D, 0L)
                : MovementModifierState.NONE;
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.WATER, FluidState.NONE, blockWorld),
                EquipmentState.NONE,
                EntityContactState.NONE,
                modifiers,
                PlayerDimensionsState.DEFAULT);
    }
}
