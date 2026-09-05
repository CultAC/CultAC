package ac.cult.cultac.checks.impl.prediction.profile;

import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.prediction.integration.BedrockMovementProfile;
import ac.cult.cultac.player.CultPlayer;

public final class MovementProfiles {
    private static final MovementProfile JAVA = new JavaMovementProfile();
    private static final MovementProfile BEDROCK = new BedrockMovementProfile();

    private MovementProfiles() {
    }

    public static MovementProfile forPlayer(CultPlayer player) {
        return player.movementPlatform == MovementPlatform.BEDROCK ? BEDROCK : JAVA;
    }
}
