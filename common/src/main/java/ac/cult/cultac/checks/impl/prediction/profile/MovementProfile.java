package ac.cult.cultac.checks.impl.prediction.profile;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.PredictionCarry;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.AuthoredMovementFrame;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.world.phys.Vec3;

public interface MovementProfile {
    default SimulationContext createContext(CultPlayer player, SimulationContext context, AuthoredMovementFrame authoredMovementFrame) {
        return context;
    }

    default Vec3 authoredPredictionStart(CultPlayer player, AuthoredMovementFrame authoredMovementFrame, Vec3 defaultStart, PredictionCarry profileCarry) {
        return defaultStart;
    }

    default void resetQueuedAuthoredInput(CultPlayer player) {
    }

    default boolean hasQueuedAuthoredInput(CultPlayer player) {
        return false;
    }

    default AuthoredMovementFrame pollQueuedAuthoredInput(CultPlayer player) {
        return null;
    }

    default AuthoredMovementFrame pollQueuedAuthoredInputBefore(CultPlayer player, AuthoredMovementFrame boundaryFrame) {
        return null;
    }

    default AuthoredMovementFrame peekQueuedAuthoredInputFor(CultPlayer player, Vec3 translatedPosition) {
        return null;
    }

    default AuthoredMovementFrame pollQueuedAuthoredInputFor(CultPlayer player, Vec3 translatedPosition) {
        return null;
    }

    default boolean shouldProcessQueuedAuthoredInputWithoutPosition(CultPlayer player) {
        return false;
    }

    default boolean shouldProcessQueuedAuthoredInputBeforeBoundary(CultPlayer player, AuthoredMovementFrame boundaryFrame) {
        return true;
    }

    default boolean shouldRunPseudoCheck(Class<?> checkClass) {
        return true;
    }

    default boolean usesFlyingExemption(CultPlayer player) {
        return true;
    }

    default boolean startsFlyingExemptionThisFrame(CultPlayer player, SimulationContext context) {
        return false;
    }

    default boolean usesSharedOffsetSetbacks(CultPlayer player) {
        return true;
    }

    default boolean shouldRunPhaseCheck(CultPlayer player) {
        return true;
    }

    default boolean usesPacketVelocityModifiers(CultPlayer player) {
        return true;
    }

    default boolean shouldUseBundledPacketProof(CultPlayer player) {
        return player.supportsBundles();
    }

    default boolean usesJavaEntityMoveStuckSpeed(CultPlayer player) {
        return true;
    }

    default void evaluatePredictionResult(CultPlayer player, PredictionResult result) {
    }

    default boolean shouldCreateVerboseLog(CultPlayer player, PredictionResult result) {
        return false;
    }

    default Vec3 debugPredictionVector(PredictionResult result) {
        if (result.getSimulationContext() != null && result.getSimulationContext().getVehicle() != null) {
            return result.getLegacyLikePredictionVector();
        }
        return result.getInputVectorAndVerticals();
    }
}
