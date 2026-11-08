package ac.cult.cultac.utils.visibility;

import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState;
import ac.cult.cultac.bedrock.prediction.state.BedrockCameraWaterState;
import ac.cult.cultac.player.CultPlayer;
import java.util.ArrayList;
import java.util.List;

/** A Bedrock client's eye height above its feet at its last tick, {@code before}, and at its latest, {@code now}. */
public record BedrockEye(double before, double now) {
    // CultPlayer#getPossibleEyeHeights at scale 1.
    private static final double[] EYE_HEIGHTS = {0.4, 1.27, 1.62};

    /** The eye heights the client may have; {@code exact} when the movement engine's camera gave them. */
    public record Heights(List<BedrockEye> eyes, boolean exact) {
        /** The lowest and the highest eye height above the feet the client may have had at either tick. */
        public double lowest() {
            double lowest = Double.POSITIVE_INFINITY;
            for (BedrockEye eye : eyes) lowest = Math.min(lowest, Math.min(eye.before, eye.now));
            return lowest;
        }

        public double highest() {
            double highest = Double.NEGATIVE_INFINITY;
            for (BedrockEye eye : eyes) highest = Math.max(highest, Math.max(eye.before, eye.now));
            return highest;
        }
    }

    /**
     * The client's eye heights above its feet at its last two ticks, as the movement engine's camera
     * (BedrockCameraWaterState) carries them into its next tick. Without that state, or for a scaled player, whose
     * camera the engine does not model, any eye height the player may have.
     */
    public static Heights of(CultPlayer player) {
        List<BedrockEye> eyes = new ArrayList<>(EYE_HEIGHTS.length);
        if (player.getScale() == 1.0F) {
            var carry = player.checkManager.getSimulationProcessor().getCurrentPredictionCarry();
            for (BedrockProfileState.Entry entry : BedrockProfileState.profileEntries(carry)) {
                BedrockCameraWaterState camera = entry.state().cameraWater();
                var eye = new BedrockEye(
                        BedrockCameraWaterState.STANDING_EYE_HEIGHT - camera.previousOffset(),
                        BedrockCameraWaterState.STANDING_EYE_HEIGHT - camera.currentOffset());
                if (!eyes.contains(eye)) eyes.add(eye);
            }
            if (!eyes.isEmpty()) return new Heights(eyes, true);
        }
        for (double height : EYE_HEIGHTS) {
            eyes.add(new BedrockEye(height * player.getScale(), height * player.getScale()));
        }
        return new Heights(eyes, false);
    }

    /** The heights from {@link #of}, or, when the engine gave none, any eye height the player may have. */
    public static Heights ofOrAny(CultPlayer player) {
        Heights heights = of(player);
        return heights.exact() ? heights : any(player);
    }

    /** Any eye height the player may have at either tick, from the lowest pose's to the standing camera's. */
    public static Heights any(CultPlayer player) {
        double scale = player.getScale();
        return new Heights(
                List.of(
                        new BedrockEye(EYE_HEIGHTS[0] * scale, EYE_HEIGHTS[0] * scale),
                        new BedrockEye(
                                BedrockCameraWaterState.STANDING_EYE_HEIGHT * scale,
                                BedrockCameraWaterState.STANDING_EYE_HEIGHT * scale)),
                false);
    }
}
