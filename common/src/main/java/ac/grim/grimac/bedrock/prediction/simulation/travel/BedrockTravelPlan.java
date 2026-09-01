package ac.grim.grimac.bedrock.prediction.simulation.travel;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockFrameState;
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
