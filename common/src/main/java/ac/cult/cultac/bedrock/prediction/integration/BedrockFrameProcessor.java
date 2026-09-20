package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.world.phys.Vec3;

/** Processes an original input frame before its transport representation is changed. */
public final class BedrockFrameProcessor {
    private BedrockFrameProcessor() { }

    public static BedrockMovementState process(CultPlayer player, BedrockAuthInputFrame original, BedrockPredictionTrigger trigger) {
        player.packetStateData.clearBedrockTranslatedMovementPermit();
        if (!original.hasMinimumStrictData() || !finite(original.getPosition())
                || !finite(original.getReportedEndOfTickVelocity())
                || !Float.isFinite(original.getYaw()) || !Float.isFinite(original.getPitch())
                || !BedrockControlInput.validControl(original)
                || !player.bedrockState.acceptMovementTick(original.getClientTick())) return null;
        BedrockAuthInputFrame frame = player.getSetbackTeleportUtil().resolveBedrockCoordinates(original);
        if (frame == null) return null;
        var processor = player.checkManager.getSimulationProcessor();
        var teleport = player.getSetbackTeleportUtil().acknowledgeBedrockTeleportFrame(frame);
        if (teleport.isTeleport()) processor.applyAcceptedBedrockTeleport(teleport);
        else if (player.getSetbackTeleportUtil().mustAcknowledgeBedrockTransportTeleport()) return null;
        if (player.checkManager.getCheck(TimerCheck.class).onBedrockAuthInput()
                == TimerCheck.BedrockAuthInputDecision.REJECT) return null;

        player.bedrockState.offerAuthInputFrame(frame);
        if (frame.hasRawInputFlag(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.START_FLYING)
                && player.canFly) player.isFlying = true;
        if (frame.hasRawInputFlag(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.STOP_FLYING)) player.isFlying = false;
        var result = processor.processBedrockAuthInputFrame(frame, trigger, teleport.getTeleportData());
        if (player.packetStateData.hasPendingRejectedBedrockTranslatedMovement()) return null;
        if (result != null) player.checkManager.doChecksWithKnownLook();
        var vehicle = result == null || result.getSimulationContext() == null
                ? null : result.getSimulationContext().getVehicle();
        var commit = vehicle == null ? processor.getCurrentPredictionCommit()
                : vehicle.bedrockPrediction == null ? null : vehicle.bedrockPrediction.commit();
        var state = commit == null ? null : BedrockProfileState.previousState(commit.carry());
        if (state == null || result == null && !processor.isBedrockSleepingStateObserved()
                && player.compensatedEntities.getSelf().getRiding() == null) return null;
        if (!state.isVehicle()) {
            player.isGliding = state.gliding();
            player.isSprinting = state.sprinting();
            player.compensatedEntities.hasSprintingAttributeEnabled = state.movementAttribute().hasSprintModifier();
        }
        return state;
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
