package ac.cult.cultac.player;

import ac.cult.cultac.checks.impl.aim.processor.AimProcessor;
import org.jetbrains.annotations.Nullable;

/**
 * A player's client as {@code /cult profile} shows it: sensitivity in that command's percent scale, NaN until known
 * and for Bedrock; {@code fastMath} null for Bedrock.
 */
public record ClientProfile(
        boolean bedrock,
        int protocol,
        String version,
        String brand,
        ClientClassification classification,
        double horizontalSensitivity,
        double verticalSensitivity,
        @Nullable Boolean fastMath) {

    /** {@code player}'s profile now; on the player's packet thread. */
    public static ClientProfile of(CultPlayer player) {
        boolean bedrock = player.isBedrockMovement();
        double horizontal = Double.NaN;
        double vertical = Double.NaN;
        if (!bedrock) {
            AimProcessor aim = player.checkManager.getCheck(AimProcessor.class);
            if (aim != null) {
                AimProcessor.Sensitivity sensitivity = aim.sensitivitySnapshot();
                horizontal = sensitivity.horizontal() * 200;
                vertical = sensitivity.vertical() * 200;
            }
        }
        return new ClientProfile(
                bedrock,
                player.getClientVersion().getProtocolVersion(),
                player.getVersionName(),
                player.getBrand(),
                player.getClientClassification(),
                horizontal,
                vertical,
                bedrock ? null : !player.isVanillaMath());
    }
}
