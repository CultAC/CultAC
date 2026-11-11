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
    public void bedReboundScalesWithTheFallSpeed() {
        BounceBlockState bed = new BounceBlockState(BounceBlockType.BED);

        assertEquals(0.75D, bed.reboundVelocityY(-1.0D), 1.0E-7D);
        assertEquals(0.75D * 1.7817025D, bed.reboundVelocityY(-1.7817025D), 1.0E-6D);
        assertEquals(3.0D, bed.reboundVelocityY(-4.0D), 1.0E-6D);
    }

    private static double bounce(double fallingVelocityY, double distanceToBed) {
        BedrockMovementState falling = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(0.5D, BED_TOP + distanceToBed, 0.5D),
                new Vec3d(0.0D, fallingVelocityY, 0.0D),
                BedrockInputFrame.idle(1L),
                BedrockCollisionFlags.AIR);
        PlacedBlockCollision bed = PlacedBlockCollision.manual(
                new BlockPosition(0, 0, 0),
                "minecraft:red_bed[facing=south,part=foot]",
                "minecraft:bed",
                List.of(new WorldCollisionBox(0.0D, 0.0D, 0.0D, 1.0D, BED_TOP, 1.0D)));
        BedrockMovementContext context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of(bed))),
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

        assertEquals(BED_TOP, result.predictedPosition().y(), 1.0E-9D);
        return result.predictedVelocity().y();
    }
}
