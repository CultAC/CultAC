package ac.cult.cultac.bedrock.prediction.simulation.travel;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameState;
import java.util.Objects;

public record BedrockTravelPlan(
    BedrockFrameState frame,
    Vec3d travelVelocity,
    BedrockTravelHorizontalControl.Step horizontal,
    BedrockMoveRequest moveRequest
) {
    public BedrockTravelPlan {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(travelVelocity, "travelVelocity");
        Objects.requireNonNull(horizontal, "horizontal");
        Objects.requireNonNull(moveRequest, "moveRequest");
    }
}
