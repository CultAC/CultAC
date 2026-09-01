package ac.grim.grimac.bedrock.prediction.integration;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.bedrock.prediction.BedrockMovementObservation;
import ac.grim.grimac.bedrock.prediction.BedrockPredictionResult;
import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.bedrock.protocol.BedrockAuthInputFrame;
import ac.grim.grimac.checks.impl.bedrock.BedrockMovement;
import ac.grim.grimac.checks.impl.prediction.AuthoredMovementFrame;
import ac.grim.grimac.checks.impl.prediction.PredictionCarry;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.checks.impl.prediction.checks.psuedo.NoFallPseudo;
import ac.grim.grimac.checks.impl.prediction.profile.MovementProfile;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.world.phys.Vec3;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

public final class BedrockMovementProfile implements MovementProfile {
    @Override
    public SimulationContext createContext(GrimPlayer player, SimulationContext context, AuthoredMovementFrame authoredMovementFrame) {
        context.attachAuthoredInput(authoredMovementFrame);
        if (player.bedrockState != null && authoredMovementFrame instanceof ac.grim.grimac.bedrock.protocol.BedrockAuthInputFrame frame) {
            context.setBedrockAuthoritativeInputTick(player.bedrockState.authoritativeInputTick(frame));
        }
        return context;
    }

    @Override
    public Vec3 authoredPredictionStart(GrimPlayer player, AuthoredMovementFrame authoredMovementFrame, Vec3 defaultStart, PredictionCarry profileStateContext) {
        BedrockMovementState profileState = BedrockProfileState.previousState(profileStateContext);
        return profileState == null ? defaultStart : toJava(profileState.physicalFeetPosition());
    }

    private static Vec3 toJava(Vec3d vec) {
        return new Vec3(vec.x(), vec.y(), vec.z());
    }

    @Override
    public boolean shouldRunPseudoCheck(Class<?> checkClass) {
        return checkClass == BedrockMovement.class
                || checkClass == NoFallPseudo.class;
    }

    @Override
    public boolean usesFlyingExemption(GrimPlayer player) {

        return hasTrustedMayFlyAbility(player);
    }

    @Override
    public boolean startsFlyingExemptionThisFrame(GrimPlayer player, SimulationContext context) {
        if (player == null || context == null || context.getBedrockInput() == null) {
            return false;
        }
        boolean swimmingInWater = context.getBedrockInput().isSwimming()
                && context.getWorldData() != null
                && context.getWorldData().getInWater().determinePessimistically();
        return startsFlyingExemptionThisFrame(
                hasTrustedMayFlyAbility(player),
                swimmingInWater,
                context.getBedrockInput());
    }

    static boolean startsFlyingExemptionThisFrame(
            boolean mayFly,
            boolean swimmingInWater,
            BedrockAuthInputFrame frame
    ) {
        return mayFly
                && !swimmingInWater
                && frame != null
                && frame.hasRawInputFlag(PlayerAuthInputData.START_FLYING)
                && !frame.hasRawInputFlag(PlayerAuthInputData.STOP_FLYING);
    }

    private static boolean hasTrustedMayFlyAbility(GrimPlayer player) {
        return player != null && player.canFly;
    }

    @Override
    public boolean usesSharedOffsetSetbacks(GrimPlayer player) {
        return false;
    }

    @Override
    public boolean shouldRunPhaseCheck(GrimPlayer player) {
        return false;
    }

    @Override
    public boolean usesPacketVelocityModifiers(GrimPlayer player) {
        return true;
    }

    @Override
    public boolean shouldUseBundledPacketProof(GrimPlayer player) {
        return false;
    }

    @Override
    public boolean usesJavaEntityMoveStuckSpeed(GrimPlayer player) {
        return false;
    }

    @Override
    public void evaluatePredictionResult(GrimPlayer player, PredictionResult result) {
    }

    @Override
    public boolean shouldCreateVerboseLog(GrimPlayer player, PredictionResult result) {
        if (player.bedrockState == null || result.isTeleport() || result.isExempt()) {
            return false;
        }

        var configManager = GrimAPI.INSTANCE.getConfigManager();
        if (!configManager.isVerboseBedrockMovement()) {
            return false;
        }

        boolean missingTrustedBedrockInput = result.getSimulationContext() != null
                && !result.getSimulationContext().hasTrustedAuthoredInput();
        if (missingTrustedBedrockInput) {
            return false;
        }

        if (result.hasEffectiveFlags()) {
            return consumeVerboseLogCooldown(player);
        }

        BedrockMovementObservation observation = currentMovementObservation(result);
        if (isCurrentMovementFlag(observation, configManager)) {
            return consumeVerboseLogCooldown(player);
        }
        if (observation != null
                && configManager.isVerboseBedrockMovementLogCleanOffsets()
                && observation.validationOffset() >= configManager.getVerboseBedrockMovementMinOffset()) {
            return consumeVerboseLogCooldown(player);
        }

        return false;
    }

    @Override
    public Vec3 debugPredictionVector(PredictionResult result) {
        return result.getAcceptedClosestToTarget();
    }

    private static boolean isCurrentMovementFlag(
            BedrockMovementObservation observation,
            ac.grim.grimac.manager.config.BaseConfigManager configManager
    ) {
        return observation != null
                && observation.validationOffset() >= configManager.getBedrockMovementPositionFlagThreshold();
    }

    private static BedrockMovementObservation currentMovementObservation(PredictionResult result) {
        if (result == null || result.getPlayer() == null || result.getPlayer().bedrockState == null) {
            return null;
        }
        BedrockPredictionResult bedrockResult = result.getProfileResult(BedrockPredictionResult.class);
        if (bedrockResult != null) {
            return bedrockResult.observation();
        }
        return null;
    }

    private static boolean consumeVerboseLogCooldown(GrimPlayer player) {
        return player.bedrockState != null
                && player.bedrockState.consumeVerboseMovementLogCooldown(
                GrimAPI.INSTANCE.getConfigManager().getVerboseBedrockMovementCooldownSeconds());
    }

}
