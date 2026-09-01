package ac.grim.grimac.checks.impl.prediction.pipeline;

import ac.grim.grimac.bedrock.prediction.integration.BedrockMovementEngine;
import ac.grim.grimac.bedrock.prediction.integration.BedrockMovementProfile;
import ac.grim.grimac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.grim.grimac.checks.impl.prediction.profile.JavaMovementProfile;
import ac.grim.grimac.checks.impl.prediction.profile.MovementProfile;

public final class MovementEngines {
    private MovementEngines() {
    }

    public static MovementEngine requireForProfile(MovementProfile profile) {
        if (profile instanceof JavaMovementProfile) {
            return JavaMovementEngine.INSTANCE;
        }
        if (profile instanceof BedrockMovementProfile) {
            return BedrockMovementEngine.INSTANCE;
        }
        throw new IllegalStateException("missing movement engine for profile " + profile.getClass().getName());
    }
}
