package ac.cult.cultac.bedrock.prediction.simulation.travel;

import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameFacts;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInputControl;

final class BedrockPlayerAirTravelMovement {
    private BedrockPlayerAirTravelMovement() {
    }

    static BedrockTravelHorizontalControl.Step resolveHorizontal(BedrockFrameState frame) {
        BedrockFrameFacts frameFacts = frame.frameFacts();
        BedrockTravelInputControl.InputControlState inputControl = frame.control();

        return BedrockTravelHorizontalControl.resolveNormalTravel(
            frame.input().previousState(),
            frame.input().inputFrame(),
            frameFacts.context(),
            frameFacts.effectState(),
            frameFacts.standingSurfaceState(),
            frameFacts.climb(),
            frameFacts.inPowderSnow(),
            inputControl.sprintSpeedInput(),
            inputControl.moveInputScale(),
            false
        );
    }
}
