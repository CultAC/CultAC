package ac.grim.grimac.bedrock.prediction.integration;

import ac.grim.grimac.bedrock.prediction.BedrockMovementObservation;
import ac.grim.grimac.bedrock.prediction.BedrockPredictionResult;
import ac.grim.grimac.bedrock.prediction.api.BedrockMovementResult;
import ac.grim.grimac.bedrock.prediction.integration.BedrockProfileState.Entry;
import ac.grim.grimac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import java.util.ArrayList;
import java.util.List;

final class BedrockRejectedVerticalFlags {
    private BedrockRejectedVerticalFlags() {
    }

    static List<Entry> apply(
        BedrockPredictionResult bedrockResult,
        List<Entry> entries,
        double positionFlagThreshold
    ) {
        BedrockMovementObservation observation = bedrockResult.observation();
        BedrockMovementResult movementResult = bedrockResult.movementResult();
        if (observation == null || movementResult == null || entries.isEmpty()
            || observation.verticalOffset() < positionFlagThreshold) {
            return entries;
        }
        BedrockMovementState admitted = movementResult.predictedState();
        BedrockCollisionFlags admittedFlags = admitted.collisionFlags();
        boolean changed = false;
        List<Entry> corrected = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            corrected.add(entry.withState(realign(entry.state(), admitted, admittedFlags)));
            changed = true;
        }
        return changed ? List.copyOf(corrected) : entries;
    }

    private static BedrockMovementState realign(
        BedrockMovementState state,
        BedrockMovementState admitted,
        BedrockCollisionFlags admittedFlags
    ) {
        BedrockCollisionFlags flags = state.collisionFlags();
        BedrockCollisionFlags realigned = new BedrockCollisionFlags(
            admittedFlags.onGround(),
            flags.horizontalCollision(),
            admittedFlags.verticalCollision(),
            flags.horizontalBlockContact(),
            flags.liquidClimbOut(),
            admittedFlags.verticalCollisionBelow(),
            flags.xCollision(),
            flags.zCollision()
        );
        return realigned.equals(flags) && state.movementBranch() == admitted.movementBranch()
            ? state
            : state.withVelocityAndCollisionFlags(state.velocity(), realigned)
                .withMovementBranch(admitted.movementBranch());
    }
}
