package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementUpdate;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockRiptideContinuationTest {
    @Test
    public void localSpinPoseResizesBeforeWaterSensingAndTravelSelection() {
        Vec3d carriedVelocity = new Vec3d(-0.1572998046875D, 0.22865478515625D, -0.9177398681640625D);
        BedrockMovementState state = activeSpinState(
            new Vec3d(257.4089660644531D, 82.6134033203125D, -51.02273941040039D),
            carriedVelocity, false, true, 2L);
        BedrockInputFrame frame = new BedrockInputFrame(
            1309L, 169.05203F, -8.839905F, true, false, true,
            Set.of("JUMPING", "JUMP_CURRENT_RAW", "WANT_UP"));

        BedrockFrameState prepared = prepare(state, frame, Medium.AIR);

        assertEquals(carriedVelocity.y() + 0.04F, prepared.travelVelocity().y(), 1.0E-12D);
        assertEquals(0.6000000238418579D, prepared.frameFacts().movementDimensions().height(), 0.0D);
        assertTrue(prepared.branch().waterTravel());
    }

    @Test
    public void retainedReleaseWaterDoesNotLeakIntoStandingMobJump() {
        Vec3d carriedVelocity = new Vec3d(0.0366455078125D, 0.105638427734375D, 0.002349853515625D);
        BedrockMovementState state = inactiveState(
            new Vec3d(259.332275390625D, 82.67180633544922D, -90.5318603515625D),
            carriedVelocity,
            true);
        BedrockInputFrame frame = new BedrockInputFrame(
            1349L, 87.87891F, -10.027222F, true, false, true,
            Set.of("JUMPING", "JUMP_CURRENT_RAW", "WANT_UP"));

        BedrockFrameState prepared = prepare(state, frame, Medium.AIR);

        assertEquals(carriedVelocity.y(), prepared.travelVelocity().y(), 0.0D);
        assertFalse(prepared.branch().waterTravel());
    }

    private static BedrockFrameState prepare(
        BedrockMovementState state,
        BedrockInputFrame frame,
        Medium medium
    ) {
        Vec3d feet = state.physicalFeetPosition();
        BlockCollisionWorld world = new BlockCollisionWorld(List.of(
            PlacedBlockCollision.manual(
                new BlockPosition((int) Math.floor(feet.x()), (int) Math.floor(feet.y()), (int) Math.floor(feet.z())),
                "minecraft:water[level=0]",
                "minecraft:water",
                Map.of("liquid_depth", 0),
                List.of())
        ));
        BedrockMovementContext context = new BedrockMovementContext(
            BedrockEffectState.NONE,
            AttributeState.DEFAULT,
            new WorldContactState(medium, FluidState.NONE, world),
            new EquipmentState(0, 0, 0, 1, false, false),
            EntityContactState.NONE,
            new MovementModifierState(
                true, true, false, false, 0.05D, false, true, false, false, 0.35D, 0L),
            PlayerDimensionsState.DEFAULT);
        BedrockTravelInput input = new BedrockTravelInput(
            state,
            frame,
            frame.intent(),
            BedrockWorldSnapshot.fromContext(context),
            BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
            state.velocity(),
            BedrockTravelOptions.vanilla(false, 0.5625D));
        return BedrockFrameSystems.prepare(input, BedrockMobJumpComponentState.DEFAULT);
    }

    private static BedrockMovementState activeSpinState(
        Vec3d position,
        Vec3d velocity,
        boolean swimming,
        boolean wasInWater,
        long spinTicks
    ) {
        BedrockMovementState initial = BedrockMovementState.fromPhysicalFeet(
            position,
            velocity,
            BedrockInputFrame.idle(1L),
            BedrockCollisionFlags.AIR,
            Medium.AIR);
        return initial.advance(new BedrockMovementUpdate(
            initial.physicalFeetPosition(),
            velocity,
            BedrockInputFrame.idle(2L),
            BedrockCollisionFlags.AIR,
            initial.boundingBoxMode(),
            initial.playerDimensions(),
            0.0F,
            0L,
            new BedrockMovementUpdate.Glide(false, false),
            swimming,
            0.0D,
            new BedrockMovementUpdate.Riptide(0L, true, spinTicks),
            new BedrockMovementUpdate.ItemUse(false, 0L)))
            .withWasInWaterFlag(wasInWater);
    }

    private static BedrockMovementState inactiveState(
        Vec3d position,
        Vec3d velocity,
        boolean wasInWater
    ) {
        BedrockMovementState initial = BedrockMovementState.fromPhysicalFeet(
            position,
            velocity,
            BedrockInputFrame.idle(1L),
            BedrockCollisionFlags.AIR,
            Medium.AIR);
        return initial.withWasInWaterFlag(wasInWater);
    }
}
