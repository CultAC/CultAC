package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import java.util.Objects;

public record BedrockFrameState(
    BedrockTravelInput input,
    BedrockInputIntent inputIntent,
    BedrockFrameFacts frameFacts,
    BedrockGlideState gliding,
    BedrockTravelBranch branch,
    boolean travelActorSprinting,
    BedrockTravelInputControl.InputControlState control,
    BedrockRiptideMovement.Step riptide,
    BedrockMobJumpComponentState mobJumpComponent,
    Vec3d travelVelocity,
    BedrockMobJump mobJump
) {
    public BedrockFrameState {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(inputIntent, "inputIntent");
        Objects.requireNonNull(frameFacts, "frameFacts");
        Objects.requireNonNull(gliding, "gliding");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(riptide, "riptide");
        Objects.requireNonNull(mobJumpComponent, "mobJumpComponent");
        Objects.requireNonNull(travelVelocity, "travelVelocity");
        Objects.requireNonNull(mobJump, "mobJump");
    }

    public boolean postMoveActorSprinting() {
        return inputIntent.sprint().nextActorSprinting(input.previousState().sprinting());
    }
}
