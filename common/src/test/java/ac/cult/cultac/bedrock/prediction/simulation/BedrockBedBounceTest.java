package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;

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
import ac.cult.cultac.bedrock.prediction.world.BounceBlockState;
import ac.cult.cultac.bedrock.prediction.world.BounceBlockType;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.List;
import org.junit.Test;

public final class BedrockBedBounceTest {
    private static final double BED_TOP = 0.5625D;
    private static final double SHELF_MUSHROOM_TOP = 0.6875D;

    @Test
    public void fastFallReboundsAboveThreeQuartersOfABlock() {
        // Captured fall onto a bed: 1.7817025 a tick with 0.922554 left to the bed, and the client reported
        // 1.271285 = (0.75 * 1.7817025 - 0.0390475) * 0.98.
        assertEquals(1.271285D, bounce(-1.7817025D, 0.922554D), 1.0E-6D);
    }

    @Test
    public void slowerFallsOfTheSameCaptureKeepTheirRebound() {
        assertEquals(0.44938737D, bounce(-0.64525026D, 0.4592D), 1.0E-5D);
        assertEquals(0.3022143D, bounce(-0.4212945D, 0.4141D), 1.0E-5D);
    }

    @Test
    public void shelfMushroomReboundsLikeABed() {
        // Captured landing on a shelf mushroom top (0.6875) at 0.3015347 a tick with 0.1092224 left to it: the client
        // rebounded at about 0.17038 where a plain landing gives -0.0784.
        PlacedBlockCollision mushroom = PlacedBlockCollision.manual(
                new BlockPosition(0, 0, 0),
                "minecraft:shelf_mushroom[age=0,facing=north]",
                "minecraft:shelf_mushroom",
                List.of(new WorldCollisionBox(0.1875D, 0.25D, 0.5625D, 0.8125D, SHELF_MUSHROOM_TOP, 1.0D)));
        assertEquals(
                0.17037888D,
                bounce(mushroom, SHELF_MUSHROOM_TOP, new Vec3d(0.5D, 0.0D, 0.75D), -0.3015347123146057D, 0.10922241D),
                1.0E-5D);
    }

    @Test
    public void bedReboundScalesWithTheFallSpeed() {
        BounceBlockState bed = new BounceBlockState(BounceBlockType.BED);

        assertEquals(0.75D, bed.reboundVelocityY(-1.0D), 1.0E-7D);
        assertEquals(0.75D * 1.7817025D, bed.reboundVelocityY(-1.7817025D), 1.0E-6D);
        assertEquals(3.0D, bed.reboundVelocityY(-4.0D), 1.0E-6D);
    }

    private static double bounce(double fallingVelocityY, double distanceToBed) {
        PlacedBlockCollision bed = PlacedBlockCollision.manual(
                new BlockPosition(0, 0, 0),
                "minecraft:red_bed[facing=south,part=foot]",
                "minecraft:bed",
                List.of(new WorldCollisionBox(0.0D, 0.0D, 0.0D, 1.0D, BED_TOP, 1.0D)));
        return bounce(bed, BED_TOP, new Vec3d(0.5D, 0.0D, 0.5D), fallingVelocityY, distanceToBed);
    }

    private static double bounce(
            PlacedBlockCollision block, double top, Vec3d column, double fallingVelocityY, double distanceToTop) {
        BedrockMovementState falling = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(column.x(), top + distanceToTop, column.z()),
                new Vec3d(0.0D, fallingVelocityY, 0.0D),
                BedrockInputFrame.idle(1L),
                BedrockCollisionFlags.AIR);
        BedrockMovementContext context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of(block))),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);

        BedrockMovementResult result = BedrockSimulation.move(
                falling,
                BedrockInputFrame.idle(2L),
                context,
                BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
                false,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP);

        assertEquals(top, result.predictedPosition().y(), 1.0E-9D);
        return result.predictedVelocity().y();
    }
}
