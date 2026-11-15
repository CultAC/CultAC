package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputMode;
import ac.cult.cultac.bedrock.prediction.input.BedrockPoseInputData;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockClientPoseState;
import java.util.Set;
import java.util.TreeSet;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

final class BedrockProtocolInputFrameFactory {
    BedrockInputFrame create(
            BedrockAuthInputFrame frame,
            long authoritativeInputTick,
            BedrockClientPoseState poseState,
            boolean actorGliding,
            boolean swimmingRequested,
            Set<String> extraInputData) {
        TreeSet<String> inputData = new TreeSet<>();
        addFrameInputData(inputData, frame, poseState, actorGliding);
        inputData.addAll(extraInputData);

        return new BedrockInputFrame(
                Math.max(0L, authoritativeInputTick),
                frame.getYaw(),
                frame.getPitch(),
                frame.isJumping(),
                frame.isSneaking(),
                frame.isSprinting(),
                inputData,
                swimmingRequested);
    }

    private static void addFrameInputData(
            TreeSet<String> inputData,
            BedrockAuthInputFrame frame,
            BedrockClientPoseState poseState,
            boolean actorGliding) {
        add(inputData, frame.isJumpStarted(), "START_JUMPING");
        add(inputData, frame.isJumpPressedRaw(), "JUMP_PRESSED_RAW");
        add(inputData, frame.isJumpCurrentRaw(), "JUMP_CURRENT_RAW");
        add(inputData, frame.isWantUp(), "WANT_UP");
        add(inputData, frame.isJumping(), "JUMPING");
        add(inputData, frame.isSprinting(), "SPRINTING");
        add(inputData, frame.hasRawInputFlag(PlayerAuthInputData.START_SPRINTING), "START_SPRINTING");
        add(inputData, frame.hasRawInputFlag(PlayerAuthInputData.STOP_SPRINTING), "STOP_SPRINTING");
        add(inputData, frame.hasRawInputFlag(PlayerAuthInputData.SPRINT_DOWN), "SPRINT_DOWN");
        add(inputData, poseState.swimming(), BedrockPoseInputData.SWIMMING);
        add(inputData, poseState.crawling(), BedrockPoseInputData.HORIZONTAL_POSE);
        add(inputData, actorGliding, BedrockPoseInputData.GLIDING);
        inputData.add(BedrockPoseInputData.ACTOR_POSE_SNAPSHOT);
        add(inputData, poseState.sneaking(), BedrockPoseInputData.ACTOR_SNEAKING);
        add(inputData, poseState.sleeping(), BedrockPoseInputData.ACTOR_SLEEPING);
        add(inputData, frame.isSwimming(), BedrockPoseInputData.START_SWIMMING);
        add(inputData, frame.isStopSwimming(), BedrockPoseInputData.STOP_SWIMMING);
        add(inputData, frame.isStartCrawling(), BedrockPoseInputData.START_CRAWLING);
        add(inputData, frame.isStopCrawling(), BedrockPoseInputData.STOP_CRAWLING);
        add(inputData, frame.hasRawInputFlag(PlayerAuthInputData.START_FLYING), BedrockPoseInputData.START_FLYING);
        add(inputData, frame.hasRawInputFlag(PlayerAuthInputData.STOP_FLYING), BedrockPoseInputData.STOP_FLYING);
        add(inputData, frame.isStartGliding(), BedrockPoseInputData.START_GLIDING);
        add(inputData, frame.isStopGliding(), BedrockPoseInputData.STOP_GLIDING);
        add(inputData, frame.isUsingItem(), "START_USING_ITEM");
        add(inputData, frame.hasRawInputFlag(PlayerAuthInputData.START_SPIN_ATTACK), "START_SPIN_ATTACK");
        add(inputData, frame.hasRawInputFlag(PlayerAuthInputData.STOP_SPIN_ATTACK), "STOP_SPIN_ATTACK");
        add(inputData, frame.hasRawInputFlag(PlayerAuthInputData.ASCEND_BLOCK), "ASCEND_BLOCK");
        add(inputData, frame.hasRawInputFlag(PlayerAuthInputData.DESCEND_BLOCK), "DESCEND_BLOCK");
        add(inputData, frame.hasRawInputFlag(PlayerAuthInputData.WANT_DOWN), "WANT_DOWN");
        add(inputData, frame.hasRawInputFlag(PlayerAuthInputData.DESCEND), "DESCEND");
        add(inputData, frame.hasRawInputFlag(PlayerAuthInputData.SNEAK_DOWN), "SNEAK_DOWN");
        add(inputData, frame.hasRawInputFlag(PlayerAuthInputData.SNEAKING), "SNEAKING");
        add(inputData, frame.hasRawInputFlag(PlayerAuthInputData.SNEAK_CURRENT_RAW), "SNEAK_CURRENT_RAW");
        add(inputData, frame.hasRawInputFlag(PlayerAuthInputData.START_SNEAKING), BedrockPoseInputData.START_SNEAKING);
        add(inputData, frame.hasRawInputFlag(PlayerAuthInputData.SNEAK_PRESSED_RAW), "SNEAK_PRESSED_RAW");
        add(inputData, frame.hasRawInputFlag(PlayerAuthInputData.SNEAK_TOGGLE_DOWN), "SNEAK_TOGGLE_DOWN");
        add(inputData, frame.hasRawInputFlag(PlayerAuthInputData.STOP_SNEAKING), BedrockPoseInputData.STOP_SNEAKING);
        BedrockInputMode inputMode = inputMode(frame.getInputMode());
        add(inputData, inputMode != BedrockInputMode.MOUSE, inputMode.inputData());
    }

    /** A frame that carries no input mode is read as keyboard and mouse. */
    private static BedrockInputMode inputMode(int protocolInputMode) {
        if (protocolInputMode < 0 || protocolInputMode == InputMode.MOUSE.ordinal()) return BedrockInputMode.MOUSE;
        if (protocolInputMode == InputMode.TOUCH.ordinal()) return BedrockInputMode.TOUCH;
        if (protocolInputMode == InputMode.GAMEPAD.ordinal()) return BedrockInputMode.GAMEPAD;
        return BedrockInputMode.OTHER;
    }

    private static void add(Set<String> inputData, boolean condition, String value) {
        if (condition) {
            inputData.add(value);
        }
    }
}
