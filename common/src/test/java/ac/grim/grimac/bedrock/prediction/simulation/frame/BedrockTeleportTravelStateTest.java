package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import ac.grim.grimac.bedrock.prediction.model.AttributeState;
import ac.grim.grimac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.grim.grimac.bedrock.prediction.model.BedrockEffectState;
import ac.grim.grimac.bedrock.prediction.model.EquipmentState;
import ac.grim.grimac.bedrock.prediction.model.Medium;
import ac.grim.grimac.bedrock.prediction.model.MovementModifierState;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;
import ac.grim.grimac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;
import ac.grim.grimac.bedrock.prediction.world.EntityContactState;
import ac.grim.grimac.bedrock.prediction.world.FluidState;
import ac.grim.grimac.bedrock.prediction.world.WorldContactState;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockTeleportTravelStateTest {
    @Test
    public void teleportDoesNotReusePreviousGroundTravelBranch() {
        BedrockMovementState grounded = BedrockMovementState.fromPhysicalFeet(
            Vec3d.ZERO,
            new Vec3d(0.04D, 0.0D, 0.0D),
            BedrockInputFrame.idle(1L),
            new BedrockCollisionFlags(true, false, true),
            Medium.GROUND);
        BedrockMovementState teleported = grounded.afterServerTeleport(
            new Vec3d(0.0D, 10.0D, 0.0D),
            grounded.velocity());

        BedrockInputFrame frame = BedrockInputFrame.idle(2L);
        BedrockTravelInput input = new BedrockTravelInput(
            teleported,
            frame,
            frame.intent(),
            BedrockWorldSnapshot.fromContext(airContext()),
            BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
            teleported.velocity(),
            BedrockTravelOptions.vanilla(false, 0.5625D));

        BedrockFrameState prepared = BedrockFrameSystems.prepare(
            input, BedrockMobJumpComponentState.DEFAULT);

        assertFalse(teleported.collisionFlags().onGround());
        assertTrue(teleported.movementGrounded());
        assertTrue(prepared.branch().airTravel());
        assertFalse(prepared.branch().groundTravel());
    }

    private static BedrockMovementContext airContext() {
        return new BedrockMovementContext(
            BedrockEffectState.NONE,
            AttributeState.DEFAULT,
            new WorldContactState(
                Medium.AIR,
                FluidState.NONE,
                new BlockCollisionWorld(List.of())),
            EquipmentState.NONE,
            EntityContactState.NONE,
            new MovementModifierState(
                true, true, false, 0.05D, false, false, false, false, 0.35D, 0L),
            PlayerDimensionsState.DEFAULT);
    }
}
