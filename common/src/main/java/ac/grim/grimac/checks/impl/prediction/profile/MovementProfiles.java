package ac.grim.grimac.checks.impl.prediction.profile;

import ac.grim.grimac.bedrock.MovementPlatform;
import ac.grim.grimac.bedrock.prediction.integration.BedrockMovementProfile;
import ac.grim.grimac.player.GrimPlayer;

public final class MovementProfiles {
    private static final MovementProfile JAVA = new JavaMovementProfile();
    private static final MovementProfile BEDROCK = new BedrockMovementProfile();

    private MovementProfiles() {
    }

    public static MovementProfile forPlayer(GrimPlayer player) {
        return player.movementPlatform == MovementPlatform.BEDROCK ? BEDROCK : JAVA;
    }
}
