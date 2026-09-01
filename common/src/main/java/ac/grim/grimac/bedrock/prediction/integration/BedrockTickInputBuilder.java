package ac.grim.grimac.bedrock.prediction.integration;

import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import ac.grim.grimac.bedrock.prediction.input.BedrockPoseInputData;
import ac.grim.grimac.bedrock.prediction.input.BedrockTickInput;
import ac.grim.grimac.bedrock.protocol.BedrockAuthInputFrame;
import ac.grim.grimac.player.GrimPlayer;
import java.util.Set;
import java.util.TreeSet;

final class BedrockTickInputBuilder {
    private final BedrockProtocolInputFrameFactory inputFrames = new BedrockProtocolInputFrameFactory();

    BedrockTickInput create(
            GrimPlayer player,
            BedrockAuthInputFrame frame,
            BedrockPlayerContext playerContext
    ) {
        BedrockInputFrame inputFrame = inputFrames.create(
                frame,
                player.bedrockState == null ? 0L : player.bedrockState.authoritativeInputTick(frame),
                playerContext.pose(),
                playerContext.actorGliding(),
                consumedActionInput(player, frame, playerContext.riptideLevel()));
        return new BedrockTickInput(
                inputFrame,
                BedrockVectorAdapter.toBedrock(frame.getPosition()),
                inputFrame.clientTick());
    }

    private static Set<String> consumedActionInput(
            GrimPlayer player,
            BedrockAuthInputFrame frame,
            int riptideLevel
    ) {
        if (player.bedrockState == null) {
            return Set.of();
        }
        TreeSet<String> inputData = new TreeSet<>();
        boolean releasedItem = player.bedrockState.consumeItemReleaseFor(frame);
        add(inputData, player.bedrockState.shouldStartRiptideCharge(
                frame, riptideLevel > 0), "RIPTIDE_CHARGE_START");
        if (releasedItem) {
            inputData.add("RELEASE_USING_ITEM");
            add(inputData, player.packetStateData.riptideLevel > 0, "RIPTIDE_RELEASE_VALID");
            player.bedrockState.clearRiptideUseTracking();
        }
        add(inputData, player.bedrockState.consumeStartSpinAttackFor(frame), "START_SPIN_ATTACK");
        add(inputData, player.bedrockState.consumeStopSpinAttackFor(frame), "STOP_SPIN_ATTACK");
        add(inputData, player.bedrockState.consumeStartGlidingActionFor(frame), BedrockPoseInputData.START_GLIDING_ACTION);
        add(inputData, player.bedrockState.consumeStopGlidingActionFor(frame), BedrockPoseInputData.STOP_GLIDING_ACTION);
        return inputData;
    }

    private static void add(Set<String> inputData, boolean condition, String value) {
        if (condition) {
            inputData.add(value);
        }
    }
}
