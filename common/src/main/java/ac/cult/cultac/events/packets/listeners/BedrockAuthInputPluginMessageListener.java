package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.BedrockTrustedEndOfTickVelocity;
import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputPluginMessage;
import ac.cult.cultac.bedrock.protocol.BedrockClientAction;
import ac.cult.cultac.bedrock.protocol.BedrockMoveFrame;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.PacketStateData;
import ac.cult.cultac.utils.data.TeleportAcceptData;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.phys.Vec3;

public final class BedrockAuthInputPluginMessageListener {
    @CultPacketHandler
    public void onCustomPayload(PacketReceiveEvent event, CultPlayer player, ServerboundCustomPayloadPacket packet) {
        String channel = NmsPacketUtil.payloadChannel(NmsPacketUtil.payload(packet));
        if (!BedrockAuthInputPluginMessage.CHANNEL.equals(channel)) {
            return;
        }

        event.setCancelled(true);
        if (!player.isBedrockMovement() || player.bedrockState == null) {
            return;
        }

        byte[] data = NmsPacketUtil.payloadData(event);
        BedrockAuthInputFrame frame = BedrockAuthInputPluginMessage.decode(data);
        if (frame != null && player.playerUUID.equals(frame.getPlayerUuid())) {
            processAuthInputFrame(player, frame);
            return;
        }

        var suppressed = BedrockAuthInputPluginMessage.decodeSuppressedProjection(data);
        if (suppressed != null && player.playerUUID.equals(suppressed.playerUuid())) {
            var decision = player.packetStateData.consumeSuppressedBedrockProjection(suppressed.clientTick());
            if (decision != null && decision.decision() == PacketStateData.BedrockTranslatedMovementDecision.REJECT) {
                // Geyser installs client position before GFP rebases it. Cancelling the Java projection
                // alone is insufficient: GFP also teleports the client to that rejected position.
                // Correct to the existing server anchor before its echo can authorize later movement.
                player.getSetbackTeleportUtil().executeNonSimulatingSetback();
            }
            return;
        }

        BedrockAuthInputPluginMessage.ClientActionMessage actionMessage =
                BedrockAuthInputPluginMessage.decodeClientAction(data);
        if (actionMessage != null && player.playerUUID.equals(actionMessage.playerUuid())) {
            processClientAction(player, actionMessage.action());
            return;
        }

        BedrockMoveFrame moveFrame = BedrockAuthInputPluginMessage.decodeMoveFrame(data);
        if (moveFrame != null && player.playerUUID.equals(moveFrame.playerUuid())) {
            player.bedrockState.setLastMoveFrame(player.getSetbackTeleportUtil().resolveBedrockCoordinates(moveFrame));
            return;
        }

        BedrockAuthInputPluginMessage.MetadataMessage metadata =
                BedrockAuthInputPluginMessage.decodeAcknowledgedMetadata(data);
        if (metadata != null && player.playerUUID.equals(metadata.playerUuid())) {
            player.checkManager.getSimulationProcessor().applyAcknowledgedBedrockMetadata(
                    metadata.width(), metadata.height(), metadata.gliding(), metadata.crawling(), metadata.swimming());
            return;
        }

        if (player.bedrockState != null) {
            player.bedrockState.clearTransientMovementInputState();
        }
    }

    private void processAuthInputFrame(CultPlayer player, BedrockAuthInputFrame frame) {
        Vec3 trustedVelocity = null;
        try {
            trustedVelocity = processAuthInputFrameAndSelectVelocity(player, frame);
        } finally {
            // Always publish the completion, even for a rejected frame or an
            // internal failure; a null velocity tells the bridge to keep its
            // prior trusted cache rather than PlayerAuthInputPacket.delta.
            GeyserBedrockBridgeRuntime.completeAuthInput(
                    player.user,
                    trustedVelocity);
        }
    }

    private Vec3 processAuthInputFrameAndSelectVelocity(CultPlayer player, BedrockAuthInputFrame frame) {
        BedrockAuthInputFrame resolved = player.getSetbackTeleportUtil().resolveBedrockCoordinates(frame);
        if (resolved == null) {
            player.packetStateData.rejectBedrockTranslatedMovement(frame.getClientTick());
            return null;
        }
        frame = resolved;
        SimulationProcessor simulationProcessor = player.checkManager.getSimulationProcessor();
        TeleportAcceptData acceptedTeleport = player.getSetbackTeleportUtil()
                .acknowledgeBedrockTeleportFrame(frame);
        if (acceptedTeleport.isTeleport()) {
            simulationProcessor.applyAcceptedBedrockTeleport(acceptedTeleport);
            // Teleport ticks advance actor state while skipping travel.
            // Only a matched server teleport may activate that phase gate.
        } else if (player.getSetbackTeleportUtil().mustAcknowledgeBedrockTransportTeleport()) {
            // A GFP rebase still in flight cannot change an earlier input frame's origin.
            // Once receipt is proven, require its exact echo through the existing gate.
            player.packetStateData.rejectBedrockTranslatedMovement(frame.getClientTick());
            return null;
        }

        TimerCheck.BedrockAuthInputDecision timerDecision =
                player.checkManager.getCheck(TimerCheck.class).onBedrockAuthInput();
        if (timerDecision == TimerCheck.BedrockAuthInputDecision.REJECT) {
            // Client-predicted boats and horses emit one MoveVehicle directly
            // from each auth packet. The same one-shot rejection is consumed
            // by that packet (or by the mounted Rot when no vehicle move was
            // generated); independent 20 Hz Geyser vehicle ticks have no
            // pending raw-auth rejection and remain untouched.
            player.packetStateData.rejectBedrockTranslatedMovement(frame.getClientTick());
            return null;
        }

        player.bedrockState.offerAuthInputFrame(frame);
        boolean sleeping = simulationProcessor.isBedrockSleepingStateObserved();
        PredictionResult result = simulationProcessor
                .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.AUTH_INPUT_PLUGIN_MESSAGE, acceptedTeleport.getTeleportData());
        if (result != null) {
            player.checkManager.doChecksWithKnownLook();
        }
        if (sleeping) {
            return Vec3.ZERO;
        }
        if (result == null || !isTrustedVelocityResult(
                player.getSetbackTeleportUtil().isPendingSetback())) {
            return null;
        }
        return BedrockTrustedEndOfTickVelocity.select(
                simulationProcessor.getCurrentPredictionCommit(),
                frame.getReportedEndOfTickVelocity());
    }

    static boolean isTrustedVelocityResult(boolean pendingSetback) {
        return !pendingSetback;
    }

    private void processClientAction(CultPlayer player, BedrockClientAction action) {
        switch (action) {
            case ITEM_RELEASE -> player.bedrockState.recordItemReleaseAction();
            case START_SPIN_ATTACK -> player.bedrockState.recordStartSpinAttackAction();
            case STOP_SPIN_ATTACK -> player.bedrockState.recordStopSpinAttackAction();
            case START_GLIDING -> player.bedrockState.recordStartGlidingAction();
            case STOP_GLIDING -> player.bedrockState.recordStopGlidingAction();
        }
    }

}
