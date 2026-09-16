package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.input.BedrockPoseInputData;
import ac.cult.cultac.bedrock.prediction.state.BedrockCameraWaterState;

/** Camera publication after the actor's pose and sneak actions. */
final class BedrockCameraMovement {
    private BedrockCameraMovement() {
    }

    static BedrockCameraWaterState afterActions(
        BedrockTravelInput input, BedrockCameraWaterState water,
        boolean swimming, boolean gliding, boolean spinning
    ) {
        var frame = input.inputFrame();
        var pose = input.inputIntent().pose();
        boolean capturedPose = BedrockPoseInputData.has(frame, BedrockPoseInputData.ACTOR_POSE_SNAPSHOT);
        boolean crawling = capturedPose ? pose.horizontalPose() : input.previousState().horizontalPose();
        if (pose.startCrawling()) crawling = true;
        if (pose.stopCrawling()) crawling = false;
        boolean sneaking = capturedPose
            ? BedrockPoseInputData.has(frame, BedrockPoseInputData.ACTOR_SNEAKING)
            : input.previousState().sneakingTicks() > 0L;
        if (pose.startSneaking()) sneaking = true;
        if (pose.stopSneaking()) sneaking = false;
        boolean sleeping = BedrockPoseInputData.has(frame, BedrockPoseInputData.ACTOR_SLEEPING);
        // Climbing adds no vertical camera adjustment. Head sensing uses the
        // player's X/Z, so horizontal camera adjustments do not affect it.
        return water.advanceCamera(crawling || swimming || gliding || spinning, sneaking, sleeping,
            BedrockCameraWaterState.STANDING_EYE_HEIGHT, 0.0F, 0.35F);
    }
}
