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
        BedrockTravelHorizontalControl.Step horizontal = resolveHorizontal(frame, frameFacts, inputControl, false);
        if (!stopSwimmingJumpInputEnvelope(frame)) {
            return horizontal;
        }
        BedrockTravelHorizontalControl.Step groundJumpHorizontal = resolveHorizontal(
            frame,
            frameFacts,
            inputControl,
            true
        );
        return new BedrockTravelHorizontalControl.Step(
            Math.max(horizontal.horizontalInputLimit(), groundJumpHorizontal.horizontalInputLimit()),
            groundJumpHorizontal.horizontalFriction()
        );
    }

    private static BedrockTravelHorizontalControl.Step resolveHorizontal(
        BedrockFrameState frame,
        BedrockFrameFacts frameFacts,
        BedrockTravelInputControl.InputControlState inputControl,
        boolean onGroundTravel
    ) {
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
            onGroundTravel
        );
    }

    private static boolean stopSwimmingJumpInputEnvelope(BedrockFrameState frame) {
        var intent = frame.inputIntent();
        return frame.mobJump().groundJumpRequest()
            && frame.input().previousState().swimming()
            && intent.jump().start()
            && intent.pose().stopSwimming()
            && !frame.frameFacts().inWater()
            && !frame.frameFacts().inLava();
    }
}
