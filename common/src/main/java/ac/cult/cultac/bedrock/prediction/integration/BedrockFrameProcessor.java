package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.movement.timer.DumbTimer;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;

/** Processes an original input frame before its transport representation is changed. */
public final class BedrockFrameProcessor {
    private BedrockFrameProcessor() {}

    public static BedrockMovementState process(
            CultPlayer player, BedrockAuthInputFrame original, BedrockPredictionTrigger trigger) {
        return process(player, original, trigger, () -> {});
    }

    public static BedrockMovementState process(
            CultPlayer player,
            BedrockAuthInputFrame original,
            BedrockPredictionTrigger trigger,
            Runnable rejectedMovementControls) {
        return process(player, original, trigger, rejectedMovementControls, frame -> {});
    }

    public static BedrockMovementState process(
            CultPlayer player,
            BedrockAuthInputFrame original,
            BedrockPredictionTrigger trigger,
            Runnable rejectedMovementControls,
            java.util.function.Consumer<BedrockAuthInputFrame> localBlockActions) {
        player.packetStateData.bedrockTranslatedMovement.clear();
        if (!original.hasMinimumStrictData()
                || !finite(original.getPosition())
                || !finite(original.getReportedEndOfTickVelocity())
                || !Float.isFinite(original.getYaw())
                || !Float.isFinite(original.getPitch())
                || !BedrockControlInput.validControl(original)
                || !player.bedrockState.acceptMovementTick(original.getClientTick())) return null;
        // The client steps its pistons before it moves.
        player.bedrockState.pistonBlocks.tick(player.compensatedWorld::getBlockStateIdAt);
        // Key input is independent of position, including while a teleport or
        // passenger transition blocks movement. Count and admit the tick first.
        // TODO: Check timer interactions with blocking sneaking and blocking teleports
        if (player.checkManager.getCheck(TimerCheck.class).onBedrockAuthInput()
                        == TimerCheck.BedrockAuthInputDecision.REJECT
                || player.checkManager.getListener(DumbTimer.class).onBedrockAuthInput()) return null;
        var state = processMovement(player, original, trigger, localBlockActions);
        if (state == null) {
            player.packetStateData.bedrockTranslatedMovement.reject();
            rejectedMovementControls.run();
        }
        return state;
    }

    private static BedrockMovementState processMovement(
            CultPlayer player,
            BedrockAuthInputFrame original,
            BedrockPredictionTrigger trigger,
            java.util.function.Consumer<BedrockAuthInputFrame> localBlockActions) {
        BedrockAuthInputFrame frame =
                player.getSetbackTeleportUtil().bedrockTeleports().resolveBedrockCoordinates(original);
        if (frame == null) return null;
        var processor = player.checkManager.getSimulationProcessor();
        var teleport = player.getSetbackTeleportUtil().bedrockTeleports().acknowledgeBedrockTeleportFrame(frame);
        if (teleport.isTeleport()) {
            player.checkManager
                    .getListener(DumbTimer.class)
                    .onTeleportAccepted(
                            teleport.isInitialSpawnTeleport(),
                            new Vec3(player.x, player.y, player.z)
                                    .distanceTo(teleport.getTeleportData().getLocation()));
            // A seated rider takes its seat, not the teleport, and its vehicle's tick travels on.
            if (!teleport.getTeleportData().isBedrockSeated()) {
                processor.applyAcceptedBedrockTeleport(teleport);
                player.packetStateData.bedrockTranslatedMovement.markTeleportFrame();
            }
        }
        player.bedrockState.offerAuthInputFrame(frame);
        if (frame.hasRawInputFlag(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.START_FLYING)
                && player.canFly) player.isFlying = true;
        if (frame.hasRawInputFlag(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.STOP_FLYING))
            player.isFlying = false;
        if (!teleport.isTeleport()
                && player.getSetbackTeleportUtil().bedrockTeleports().mustAcknowledgeBedrockTransportTeleport()) {
            processor.processBedrockAuthInputFrame(frame, trigger);
            return null;
        }
        localBlockActions.accept(frame);
        var startState = BedrockProfileState.previousState(processor.getCurrentPredictionCarry());
        boolean startedOnGround = startState != null && startState.collisionFlags().onGround();
        var result = processor.processBedrockAuthInputFrame(frame, trigger, teleport.getTeleportData());
        if (player.packetStateData.bedrockTranslatedMovement.isRejected()) return null;
        if (result != null) player.checkManager.doChecksWithKnownLook();
        var vehicle = result == null || result.getSimulationContext() == null
                ? null
                : result.getSimulationContext().getVehicle();
        var commit = vehicle == null
                ? processor.getCurrentPredictionCommit()
                : vehicle.bedrockPrediction == null ? null : vehicle.bedrockPrediction.commit();
        var state = commit == null ? null : BedrockProfileState.previousState(commit.carry());
        if (state == null
                || result == null
                        && !processor.isBedrockSleepingStateObserved()
                        && player.compensatedEntities.getSelf().getRiding() == null) return null;
        if (!state.isVehicle()) {
            player.isGliding = state.gliding();
            player.isSprinting = state.sprinting();
            // A flying tick that starts on the ground ends the flight of a player that cannot
            // build instantly, wherever its move ends: it asks to stop flying before its next tick.
            if (player.isFlying
                    && !player.canInstabuild
                    && player.gamemode != ac.cult.cultac.protocol.value.GameMode.SPECTATOR
                    && result != null
                    && startedOnGround) player.isFlying = false;
        }
        return state;
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
