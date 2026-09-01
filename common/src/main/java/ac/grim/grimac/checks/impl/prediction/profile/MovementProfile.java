package ac.grim.grimac.checks.impl.prediction.profile;

import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.PredictionCarry;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.checks.impl.prediction.AuthoredMovementFrame;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.world.phys.Vec3;

public interface MovementProfile {
    default SimulationContext createContext(GrimPlayer player, SimulationContext context, AuthoredMovementFrame authoredMovementFrame) {
        return context;
    }

    default Vec3 authoredPredictionStart(GrimPlayer player, AuthoredMovementFrame authoredMovementFrame, Vec3 defaultStart, PredictionCarry profileCarry) {
        return defaultStart;
    }

    default void resetQueuedAuthoredInput(GrimPlayer player) {
    }

    default boolean hasQueuedAuthoredInput(GrimPlayer player) {
        return false;
    }

    default AuthoredMovementFrame pollQueuedAuthoredInput(GrimPlayer player) {
        return null;
    }

    default AuthoredMovementFrame pollQueuedAuthoredInputBefore(GrimPlayer player, AuthoredMovementFrame boundaryFrame) {
        return null;
    }

    default AuthoredMovementFrame peekQueuedAuthoredInputFor(GrimPlayer player, Vec3 translatedPosition) {
        return null;
    }

    default AuthoredMovementFrame pollQueuedAuthoredInputFor(GrimPlayer player, Vec3 translatedPosition) {
        return null;
    }

    default boolean shouldProcessQueuedAuthoredInputWithoutPosition(GrimPlayer player) {
        return false;
    }

    default boolean shouldProcessQueuedAuthoredInputBeforeBoundary(GrimPlayer player, AuthoredMovementFrame boundaryFrame) {
        return true;
    }

    default boolean shouldRunPseudoCheck(Class<?> checkClass) {
        return true;
    }

    default boolean usesFlyingExemption(GrimPlayer player) {
        return true;
    }

    default boolean startsFlyingExemptionThisFrame(GrimPlayer player, SimulationContext context) {
        return false;
    }

    default boolean usesSharedOffsetSetbacks(GrimPlayer player) {
        return true;
    }

    default boolean shouldRunPhaseCheck(GrimPlayer player) {
        return true;
    }

    default boolean usesPacketVelocityModifiers(GrimPlayer player) {
        return true;
    }

    default boolean shouldUseBundledPacketProof(GrimPlayer player) {
        return player.supportsBundles();
    }

    default boolean usesJavaEntityMoveStuckSpeed(GrimPlayer player) {
        return true;
    }

    default void evaluatePredictionResult(GrimPlayer player, PredictionResult result) {
    }

    default boolean shouldCreateVerboseLog(GrimPlayer player, PredictionResult result) {
        return false;
    }

    default Vec3 debugPredictionVector(PredictionResult result) {
        if (result.getSimulationContext() != null && result.getSimulationContext().getVehicle() != null) {
            return result.getLegacyLikePredictionVector();
        }
        return result.getInputVectorAndVerticals();
    }
}
