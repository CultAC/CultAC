package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.BedrockPositionTranslator;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockCameraWaterState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;

/** Updates head-water state from the saved camera height. */
public final class BedrockUnderwaterSensing {
    private BedrockUnderwaterSensing() {}

    public static BedrockCameraWaterState update(
            BedrockMovementState actor,
            BedrockCameraWaterState current,
            BedrockMovementContext context,
            Vec3d feet,
            PlayerDimensionsState dimensions) {
        if (!actor.isHorse()) return update(current, context, feet, dimensions);
        boolean requested = BedrockLiquidSensing.waterSwimUpApplies(context, feet, dimensions);
        return current.sense(
                requested,
                requested
                        && BedrockLiquidGeometry.liquidPointInBlock(
                                context.worldState().blockCollisionWorld(),
                                feet.x(),
                                horseHeadY(feet, dimensions),
                                feet.z(),
                                BedrockLiquidKind.WATER));
    }

    /** The block at the point {@link #update} senses the head at. */
    public static BedrockHeadBlock headBlock(
            BedrockMovementState actor,
            BedrockCameraWaterState current,
            BedrockMovementContext context,
            Vec3d feet,
            PlayerDimensionsState dimensions) {
        if (!actor.isHorse()) return headBlock(current, context, feet);
        return BedrockHeadBlock.at(
                context.worldState().blockCollisionWorld(), feet.x(), horseHeadY(feet, dimensions), feet.z());
    }

    public static BedrockHeadBlock headBlock(
            BedrockCameraWaterState current, BedrockMovementContext context, Vec3d feet) {
        return BedrockHeadBlock.at(
                context.worldState().blockCollisionWorld(), feet.x(), cameraY(current, feet), feet.z());
    }

    public static BedrockCameraWaterState update(
            BedrockCameraWaterState current,
            BedrockMovementContext context,
            Vec3d feet,
            PlayerDimensionsState dimensions) {
        // Water contact enables head sensing independently of the travel branch.
        boolean headRequested = BedrockLiquidSensing.waterSwimUpApplies(context, feet, dimensions);
        boolean submerged = headRequested
                && BedrockLiquidGeometry.liquidPointInBlock(
                        context.worldState().blockCollisionWorld(),
                        feet.x(),
                        cameraY(current, feet),
                        feet.z(),
                        BedrockLiquidKind.WATER);
        return current.sense(headRequested, submerged);
    }

    private static float cameraY(BedrockCameraWaterState current, Vec3d feet) {
        float actorY = (float) (feet.y() + BedrockPositionTranslator.PLAYER_PACKET_Y_OFFSET);
        return current.sensingY(actorY, 0.0F, 0.0F);
    }

    private static float horseHeadY(Vec3d feet, PlayerDimensionsState dimensions) {
        return (float) feet.y() + (float) dimensions.height() * 0.85F;
    }
}
