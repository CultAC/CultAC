package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockTickInput;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockHorseMovement;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import java.util.Set;

final class BedrockTickInputBuilder {
    private final BedrockProtocolInputFrameFactory inputFrames = new BedrockProtocolInputFrameFactory();

    BedrockTickInput createBoat(CultPlayer player, BedrockAuthInputFrame frame,
                                BedrockMovementState previous, ac.cult.cultac.utils.data.packetentity.PacketEntity boat) {
        float yaw = previous == null ? boat.clientPhysicalYaw + 90.0F : previous.inputFrame().yaw();
        var input = new BedrockInputFrame(player.bedrockState.authoritativeInputTick(frame), yaw, 0, false, false, false);
        return new BedrockTickInput(input, BedrockVectorAdapter.toBedrock(frame.getPosition()), input.clientTick());
    }

    BedrockTickInput createHorse(CultPlayer player, BedrockAuthInputFrame frame,
                                BedrockMovementState previous, PacketEntityHorse horse) {
        float previousYaw = previous == null ? horse.clientPhysicalYaw : previous.inputFrame().yaw();
        BedrockInputFrame input = new BedrockInputFrame(player.bedrockState.authoritativeInputTick(frame),
                BedrockHorseMovement.yawAfterControl(previousYaw, frame.getYaw()), frame.getPitch() * 0.5F,
                false, false, false);
        return new BedrockTickInput(input, BedrockVectorAdapter.toBedrock(frame.getPosition()), input.clientTick());
    }

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
