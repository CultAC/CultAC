package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockTickInput;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.player.CultPlayer;
import java.util.Set;

final class BedrockTickInputBuilder {
    private final BedrockProtocolInputFrameFactory inputFrames = new BedrockProtocolInputFrameFactory();

    BedrockTickInput create(
            CultPlayer player,
            BedrockAuthInputFrame frame,
            BedrockPlayerContext playerContext
    ) {
        BedrockInputFrame inputFrame = inputFrames.create(
                frame,
                player.bedrockState == null ? 0L : player.bedrockState.authoritativeInputTick(frame),
                playerContext.pose(),
                playerContext.actorGliding(),
                player.bedrockState != null && player.bedrockState.isSwimmingRequested(),
                player.bedrockState == null ? Set.of() : player.bedrockState.actionInputFor(frame));
        return new BedrockTickInput(
                inputFrame,
                BedrockVectorAdapter.toBedrock(frame.getPosition()),
                inputFrame.clientTick());
    }

}
