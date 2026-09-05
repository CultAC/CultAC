package ac.cult.cultac.bedrock.prediction;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockMoveFrame;
import ac.cult.cultac.bedrock.protocol.BedrockMoveVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.NumFormatter;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.world.phys.Vec3;
import org.bukkit.ChatColor;

public final class BedrockPredictionDebug {
    private BedrockPredictionDebug() {
    }

    public static BedrockMovementObservation currentObservation(CultPlayer player, PredictionResult result) {
        if (!player.isBedrockMovement()
                || player.bedrockState == null
                || result == null
                || result.getSimulationContext() == null
                || result.getSimulationContext().getBedrockInput() == null) {
            return null;
        }
        BedrockPredictionResult bedrockResult = result.getProfileResult(BedrockPredictionResult.class);
        if (bedrockResult == null || bedrockResult.movementResult() == null || bedrockResult.observation() == null) {
            return null;
        }
        long currentTick = result.getSimulationContext().getBedrockInputTick();
        long predictionTick = bedrockResult.movementResult().predictedState().clientTick();
        return currentTick == predictionTick ? bedrockResult.observation() : null;
    }

    private static BedrockMovementResult movementResult(BedrockPredictionResult result) {
        return result == null ? null : result.movementResult();
    }

    public static Vec3 toJavaVec(Vec3d vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    public static MovementDebugView movementDebugView(
            CultPlayer player,
            PredictionResult result,
            Vec3 javaPredictionVector
    ) {
        BedrockMovementObservation observation = currentObservation(player, result);
        boolean missingObservation = player.isBedrockMovement() && observation == null;
        double offset = missingObservation ? Double.NaN
                : observation == null ? result.getOffset() : observation.validationOffset();
        Vec3 predictedWithInputs = observation == null
                ? javaPredictionVector
                : toJavaVec(observation.predictedDelta());
        Vec3 target = observation == null
                ? result.getTarget()
                : toJavaVec(observation.actualDelta());
        return new MovementDebugView(missingObservation, offset, predictedWithInputs, target);
    }

    public static String formatConsoleDebug(
            CultPlayer player,
            PredictionComplete predictionComplete,
            ChatColor labelColor,
            ChatColor color
    ) {
        if (!player.isBedrockMovement() || player.bedrockState == null || predictionComplete.getPredictionResult() == null) {
            return null;
        }

        PredictionResult result = predictionComplete.getPredictionResult();
        SimulationContext context = result.getSimulationContext();
        BedrockPlayerState state = player.bedrockState;
        BedrockAuthInputFrame frame = context == null ? null : context.getBedrockInput();
        BedrockAuthInputFrame displayFrame = frame == null ? state.getLastOfferedFrame() : frame;
        BedrockMoveVector moveVector = displayFrame == null || displayFrame.getMoveVector() == null ? BedrockMoveVector.ZERO : displayFrame.getMoveVector();
        long tick = displayFrame == null ? -1L : displayFrame.getClientTick();

        StringBuilder builder = new StringBuilder(labelColor + "B: " + color);
        BedrockPredictionResult bedrockResult = result.getProfileResult(BedrockPredictionResult.class);
        builder.append(" setbacks=").append(state.shouldEnforceSetbacks());
        builder.append(" frame=").append(frame == null ? "no" : "yes");
        builder.append(" tick=").append(tick);
        builder.append(" mv=").append(formatMoveVector(moveVector));
        builder.append(" offset=").append(NumFormatter.formatNumberStandard(result.getOffset()).trim());
        BedrockMovementResult movementResult = movementResult(bedrockResult);
        BedrockMovementObservation observation = currentObservation(player, predictionComplete.getPredictionResult());
        if (movementResult != null && observation != null) {
            builder.append(" predictedPos=")
                    .append(formatVectorDebug(observation.predictedPosition()));
            builder.append(" packetPos=")
                    .append(formatVectorDebug(observation.actualPosition()));
            builder.append(" predictedVel=")
                    .append(formatOffsetDebug(observation.velocityOffset()));
            builder.append(" predictedTick=").append(movementResult.predictedState().clientTick());
            builder.append(" cause=ENGINE_PHYSICS");
            builder.append(" correction=SET_POSITION");
        }
        builder.append(" authFrame=\"").append(escapeVerboseValue(state.getLastAuthInputMatchStatus())).append('"');
        return builder.toString();
    }

    public static void sendMovementVerbose(CultPlayer player, PredictionResult result, String debugLog) {
        if (!player.isBedrockMovement()
                || player.bedrockState == null
                || result == null) {
            return;
        }

        publishMovementDebug(player, result, debugLog);
        if (!CultAPI.INSTANCE.getConfigManager().isVerboseBedrockMovement()
                || result.isExempt()
                || !result.isProfileVerboseLog()) {
            return;
        }

        BedrockPlayerState state = player.bedrockState;
        BedrockPredictionResult bedrockResult = result.getProfileResult(BedrockPredictionResult.class);

        SimulationContext context = result.getSimulationContext();
        BedrockAuthInputFrame frame = context == null ? null : context.getBedrockInput();
        BedrockAuthInputFrame displayFrame = frame == null ? state.getLastOfferedFrame() : frame;
        BedrockMoveVector moveVector = displayFrame == null || displayFrame.getMoveVector() == null ? BedrockMoveVector.ZERO : displayFrame.getMoveVector();
        long tick = displayFrame == null ? -1L : displayFrame.getClientTick();

        StringBuilder message = new StringBuilder();
        message.append("%prefix% &7Bedrock movement");
        message.append(" &8log=&f").append(result.getIdentifier());
        message.append(" &8setbacks=&f").append(state.shouldEnforceSetbacks());
        message.append(" &8frame=&f").append(frame == null ? "no" : "yes");
        message.append(" &8tick=&f").append(tick);
        message.append(" &8mv=&f").append(formatMoveVector(moveVector));
        BedrockMovementResult movementResult = movementResult(bedrockResult);
        BedrockMovementObservation observation = currentObservation(player, result);
        if (movementResult != null && observation != null) {
            message.append(" &8predictedPos=&f")
                    .append(formatVectorDebug(observation.predictedPosition()));
            message.append(" &8packetPos=&f")
                    .append(formatVectorDebug(observation.actualPosition()));
            message.append(" &8predictedVel=&f")
                    .append(formatOffsetDebug(observation.velocityOffset()));
            message.append(" &8predictedTick=&f")
                    .append(movementResult.predictedState().clientTick());
            message.append(" &8cause=&f")
                    .append("ENGINE_PHYSICS");
            message.append(" &8correction=&f")
                    .append("SET_POSITION");
        }
        message.append(" &8flags=&f").append(result.getEffectiveFlagCount());
        message.append(" &8flagDetails=&f\"").append(escapeVerboseValue(formatEffectiveFlags(result))).append('"');
        message.append(" &8javaExempt=&f").append(result.isExempt() ? "yes" : "no");
        message.append(" &8authFrame=&f\"").append(escapeVerboseValue(state.getLastAuthInputMatchStatus())).append('"');

        CultAPI.INSTANCE.getAlertManager().sendVerbose(
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(message.toString()), null);
    }

    public static boolean shouldRecordMovementDebug(CultPlayer player, PredictionResult result) {
        if (player == null || player.bukkitPlayer == null || !player.isBedrockMovement() || player.bedrockState == null || result == null) {
            return false;
        }
        BedrockPredictionResult bedrockResult = result.getProfileResult(BedrockPredictionResult.class);
        if (bedrockResult == null || bedrockResult.movementResult() == null) {
            return false;
        }
        Function<Object, Object> active = CultAPI.INSTANCE.getExternalAPI()
                .getFunction("cultac.bedrock.movementDebug.active");
        return active != null && Boolean.TRUE.equals(active.apply(player.bukkitPlayer.getName()));
    }

    private static void publishMovementDebug(CultPlayer player, PredictionResult result, String debugLog) {
        if (player.bukkitPlayer == null || result == null || debugLog == null) {
            return;
        }
        BedrockPredictionResult bedrockResult = result.getProfileResult(BedrockPredictionResult.class);
        if (bedrockResult == null || bedrockResult.movementResult() == null) {
            return;
        }
        Function<Object, Object> active = CultAPI.INSTANCE.getExternalAPI()
                .getFunction("cultac.bedrock.movementDebug.active");
        Function<Object, Object> record = CultAPI.INSTANCE.getExternalAPI()
                .getFunction("cultac.bedrock.movementDebug.record");
        if (active == null || record == null) {
            return;
        }
        String username = player.bukkitPlayer.getName();
        if (!Boolean.TRUE.equals(active.apply(username))) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("username", username);
        event.put("identifier", result.getIdentifier());
        event.put("debugLog", debugLog);
        record.apply(event);
    }

    public static void appendDetails(CultPlayer player, StringBuilder sb, PredictionResult result) {
        if (!player.isBedrockMovement() || player.bedrockState == null) {
            return;
        }

        BedrockPlayerState state = player.bedrockState;
        BedrockPredictionResult bedrockResult = result.getProfileResult(BedrockPredictionResult.class);
        SimulationContext context = result.getSimulationContext();
        BedrockAuthInputFrame frame = context == null ? null : context.getBedrockInput();
        BedrockMoveFrame moveFrame = state.getLastMoveFrame();

        sb.append("\n\n=============== bedrock =================\n\n");
        sb.append("Setback enforcement: ").append(state.shouldEnforceSetbacks()).append('\n');
        sb.append("Protocol: ").append(state.getProtocolVersion()).append('\n');
        sb.append("Auth frame pairing: ").append(state.getLastAuthInputMatchStatus()).append('\n');
        sb.append("Bounding box tracking: ").append(state.boundingBoxStatus()).append('\n');
        sb.append("Prediction recorded: ").append(bedrockResult != null && bedrockResult.movementResult() != null).append('\n');
        sb.append("Context input tick: ").append(context == null ? "none" : context.getBedrockInputTick()).append('\n');

        if (frame != null) {
            appendAuthFrame(sb, frame);
        } else {
            sb.append("Auth frame: none attached to this movement\n");
        }

        if (moveFrame != null) {
            sb.append("Last MovePlayer frame: tick=").append(moveFrame.clientTick())
                    .append(" position=").append(moveFrame.position())
                    .append(" yaw=").append(moveFrame.yaw())
                    .append(" pitch=").append(moveFrame.pitch())
                    .append(" headYaw=").append(moveFrame.headYaw())
                    .append('\n');
        }

        BedrockMovementObservation observation = currentObservation(player, result);
        appendPredictionResult(sb, movementResult(bedrockResult), observation);

    }

    private static void appendPredictionResult(
            StringBuilder sb,
            BedrockMovementResult result,
            BedrockMovementObservation observation
    ) {
        if (result == null) {
            sb.append("Prediction result: none\n");
            return;
        }

        sb.append("Prediction result: positionOffset=").append(observation == null ? 0.0D : observation.validationOffset())
                .append(" rawPositionOffset=").append(observation == null ? 0.0D : observation.positionOffset())
                .append(" velocityOffset=").append(observation == null ? 0.0D : observation.velocityOffset())
                .append('\n');
        sb.append("Prediction packet position: ").append(result.predictedState().bedrockPacketPosition())
                .append(" velocity=").append(result.predictedState().velocity())
                .append(" movementBranch=").append(result.predictedState().movementBranch())
                .append(" powderTicks=").append(result.predictedState().powderSnowTicks())
                .append(" gliding=").append(result.predictedState().gliding())
                .append(" swimming=").append(result.predictedState().swimming())
                .append(" swimAmount=").append(result.predictedState().swimAmount())
                .append(" fallFlyTicks=").append(result.predictedState().fallFlyTicks())
                .append(" riptideChargeTicks=").append(result.predictedState().riptideChargeTicks())
                .append(" riptideSpinActive=").append(result.predictedState().riptideSpinActive())
                .append(" riptideSpinTicks=").append(result.predictedState().riptideSpinTicks())
                .append(" boundingMode=").append(result.predictedState().boundingBoxMode())
                .append(" dimensions=").append(result.predictedState().playerDimensions())
                .append(" explicitDimensions=").append(result.predictedState().explicitPlayerDimensions())
                .append(" pendingSlowdown=").append(result.predictedState().pendingBlockMovementSlowdownState())
                .append('\n');
        sb.append("Prediction previous state: feet=").append(result.previousState().physicalFeetPosition())
                .append(" velocity=").append(result.previousState().velocity())
                .append(" flags=").append(result.previousState().collisionFlags())
                .append(" movementBranch=").append(result.previousState().movementBranch())
                .append(" tick=").append(result.previousState().clientTick())
                .append(" simulationTick=").append(result.previousState().simulationTick())
                .append(" lastMoveSq=").append(result.previousState().lastPhysicalDisplacementSquared())
                .append(" powderTicks=").append(result.previousState().powderSnowTicks())
                .append(" fallDistance=").append(result.previousState().fallDistance())
                .append(" gliding=").append(result.previousState().gliding())
                .append(" swimming=").append(result.previousState().swimming())
                .append(" swimAmount=").append(result.previousState().swimAmount())
                .append(" fallFlyTicks=").append(result.previousState().fallFlyTicks())
                .append(" riptideChargeTicks=").append(result.previousState().riptideChargeTicks())
                .append(" riptideSpinActive=").append(result.previousState().riptideSpinActive())
                .append(" riptideSpinTicks=").append(result.previousState().riptideSpinTicks())
                .append(" boundingMode=").append(result.previousState().boundingBoxMode())
                .append(" dimensions=").append(result.previousState().playerDimensions())
                .append(" explicitDimensions=").append(result.previousState().explicitPlayerDimensions())
                .append(" pendingSlowdown=").append(result.previousState().pendingBlockMovementSlowdownState())
                .append(" input=").append(result.previousState().inputFrame())
                .append('\n');
        Vec3d predictedDelta = result.predictedState().physicalFeetPosition().subtract(result.previousState().physicalFeetPosition());
        sb.append("Prediction selected frame: input=").append(result.predictedState().inputFrame())
                .append(" feetDelta=").append(predictedDelta)
                .append(" fallDistance=").append(result.predictedState().fallDistance())
                .append('\n');
        sb.append("Movement world: medium=").append(result.movementContext().worldState().medium())
                .append(" fluid=").append(result.movementContext().worldState().fluidState())
                .append(" blockWorldEmpty=").append(result.movementContext().worldState().blockCollisionWorld().isEmpty())
                .append('\n');
        var blockWorld = result.movementContext().worldState().blockCollisionWorld();
        sb.append("Movement block snapshot: blocks=").append(blockWorld.blocks().size())
                .append(" collisionBoxes=").append(blockWorld.collisionBoxes().size())
                .append(" waterBlocks=").append(blockWorld.waterBlocks().size())
                .append(" lavaBlocks=").append(blockWorld.lavaBlocks().size())
                .append('\n');
        sb.append("Movement modifiers: entity=").append(result.movementContext().entityContactState())
                .append(" modifier=").append(result.movementContext().modifierState())
                .append('\n');
        if (observation != null) {
            sb.append("Bedrock raw prediction vectors: P:").append(observation.rawPredictedDelta())
                    .append(" A:").append(observation.actualDelta())
                    .append(" O:").append(observation.rawPositionDelta())
                    .append(" rawOffset=").append(observation.rawPositionOffset())
                    .append('\n');
            sb.append("Bedrock packet prediction vectors: P:").append(observation.predictedDelta())
                    .append(" A:").append(observation.actualDelta())
                    .append(" O:").append(observation.positionDelta())
                    .append('\n');
            sb.append("Bedrock observed packet position: ").append(observation.actualPosition())
                    .append(" positionOffset=").append(observation.validationOffset())
                    .append(" rawPositionOffset=").append(observation.positionOffset())
                    .append(" velocityOffset=").append(observation.velocityOffset())
                    .append('\n');
        } else {
            sb.append("Bedrock observed packet position: none\n");
        }
    }

    private static void appendAuthFrame(StringBuilder sb, BedrockAuthInputFrame frame) {
        sb.append("Auth frame: tick=").append(frame.getClientTick())
                .append(" protocol=").append(frame.getProtocolVersion())
                .append(" inputMode=").append(frame.getInputMode())
                .append(" playMode=").append(frame.getPlayMode())
                .append(" position=").append(frame.getPosition())
                .append(" delta=").append(frame.getDelta())
                .append(" yaw=").append(frame.getYaw())
                .append(" pitch=").append(frame.getPitch())
                .append(" headYaw=").append(frame.getHeadYaw())
                .append(" move=").append(frame.getMoveVector())
                .append(" rawFlags=0x").append(Long.toHexString(frame.getRawInputFlags()))
                .append(":").append(Long.toHexString(frame.getRawInputFlagsHigh()))
                .append(" jumping=").append(frame.isJumping())
                .append(" jumpStarted=").append(frame.isJumpStarted())
                .append(" jumpPressedRaw=").append(frame.isJumpPressedRaw())
                .append(" jumpCurrentRaw=").append(frame.isJumpCurrentRaw())
                .append(" wantUp=").append(frame.isWantUp())
                .append(" sneaking=").append(frame.isSneaking())
                .append(" sprinting=").append(frame.isSprinting())
                .append(" swimming=").append(frame.isSwimming())
                .append(" stopSwimming=").append(frame.isStopSwimming())
                .append(" startGliding=").append(frame.isStartGliding())
                .append(" stopGliding=").append(frame.isStopGliding())
                .append(" usingItem=").append(frame.isUsingItem())
                .append(" blockAction=").append(frame.hasBlockAction())
                .append(" authority=").append(frame.getAuthorityMode())
                .append(" rewindCorrectionId=").append(frame.getRewindCorrectionId())
                .append('\n');
    }

    private static String escapeVerboseValue(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String formatEffectiveFlags(PredictionResult result) {
        if (result == null || !result.hasEffectiveFlags()) {
            return "[]";
        }
        return result.getFlags().stream()
                .filter(flag -> flag != null)
                .map(flag -> flag.getCheck().getCheckName()
                        + "{severity=" + flag.getSeverity()
                        + ",verbose=\"" + escapeVerboseValue(flag.getVerbose().getString()) + "\"}")
                .toList()
                .toString();
    }

    private static String formatOffsetDebug(double offset) {
        return Double.toString(offset);
    }

    private static String formatVectorDebug(Vec3d vector) {
        if (vector == null) {
            return "(null)";
        }
        return String.format(Locale.ROOT, "(%.5f,%.5f,%.5f)", vector.x(), vector.y(), vector.z());
    }

    private static String formatMoveVector(BedrockMoveVector vector) {
        return String.format(Locale.ROOT, "(%.3f,%.3f)", vector.x(), vector.z());
    }

    public record MovementDebugView(
            boolean missingObservation,
            double offset,
            Vec3 predictedWithInputs,
            Vec3 target
    ) {
    }
}
