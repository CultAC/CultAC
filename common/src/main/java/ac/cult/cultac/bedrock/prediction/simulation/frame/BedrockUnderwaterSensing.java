package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.BedrockPositionTranslator;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockCameraWaterState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;

/** Updates head-water state from the saved camera height. */
public final class BedrockUnderwaterSensing {
    private BedrockUnderwaterSensing() {
    }

    public static BedrockCameraWaterState update(
        BedrockCameraWaterState current, BedrockMovementContext context,
        Vec3d feet, PlayerDimensionsState dimensions
    ) {
        // Water contact enables head sensing independently of the travel branch.
        boolean headRequested = BedrockLiquidSensing.waterSwimUpApplies(context, feet, dimensions);
        float actorY = (float) (feet.y() + BedrockPositionTranslator.PLAYER_PACKET_Y_OFFSET);
        float cameraY = current.sensingY(actorY, 0.0F, 0.0F);
        boolean submerged = headRequested && BedrockLiquidGeometry.liquidPointInBlock(
            context.worldState().blockCollisionWorld(), feet.x(), cameraY, feet.z(), BedrockLiquidKind.WATER);
        return current.sense(headRequested, submerged);
    }
}
