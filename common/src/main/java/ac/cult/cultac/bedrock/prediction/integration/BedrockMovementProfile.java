package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.prediction.BedrockMovementObservation;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.checks.impl.prediction.AuthoredMovementFrame;
import ac.cult.cultac.checks.impl.prediction.PredictionCarry;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoFallPseudo;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfile;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.world.phys.Vec3;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

public final class BedrockMovementProfile implements MovementProfile {
    @Override
    public SimulationContext createContext(CultPlayer player, SimulationContext context, AuthoredMovementFrame authoredMovementFrame) {
        context.attachAuthoredInput(authoredMovementFrame);
        if (player.bedrockState != null && authoredMovementFrame instanceof ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame frame) {
            context.setBedrockAuthoritativeInputTick(player.bedrockState.authoritativeInputTick(frame));
        }
        return context;
    }

    @Override
    public Vec3 authoredPredictionStart(CultPlayer player, AuthoredMovementFrame authoredMovementFrame, Vec3 defaultStart, PredictionCarry profileStateContext) {
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
    public boolean usesFlyingExemption(CultPlayer player) {

        return hasTrustedMayFlyAbility(player);
    }

    @Override
    public boolean startsFlyingExemptionThisFrame(CultPlayer player, SimulationContext context) {
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

    private static boolean hasTrustedMayFlyAbility(CultPlayer player) {
        return player != null && player.canFly;
    }

    @Override
    public boolean usesSharedOffsetSetbacks(CultPlayer player) {
        return false;
    }

    @Override
    public boolean shouldRunPhaseCheck(CultPlayer player) {
        return true;
    }

    @Override
    public boolean usesPacketVelocityModifiers(CultPlayer player) {
        return true;
    }

    @Override
    public boolean shouldUseBundledPacketProof(CultPlayer player) {
        return false;
    }

    @Override
    public boolean usesJavaEntityMoveStuckSpeed(CultPlayer player) {
        return false;
    }

    @Override
    public void evaluatePredictionResult(CultPlayer player, PredictionResult result) {
    }

    @Override
    public boolean shouldCreateVerboseLog(CultPlayer player, PredictionResult result) {
        if (player.bedrockState == null || result.isTeleport() || result.isExempt()) {
            return false;
        }

        var configManager = CultAPI.INSTANCE.getConfigManager();
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
            ac.cult.cultac.manager.config.BaseConfigManager configManager
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

    private static boolean consumeVerboseLogCooldown(CultPlayer player) {
        return player.bedrockState != null
                && player.bedrockState.consumeVerboseMovementLogCooldown(
                CultAPI.INSTANCE.getConfigManager().getVerboseBedrockMovementCooldownSeconds());
    }

}
