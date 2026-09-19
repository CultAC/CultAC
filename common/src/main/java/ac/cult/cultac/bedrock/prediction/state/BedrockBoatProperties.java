package ac.cult.cultac.bedrock.prediction.state;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import java.util.Set;

/** Immutable client-visible boat metadata, published at an acknowledged packet boundary. */
public record BedrockBoatProperties(long runtimeId, PlayerDimensionsState dimensions,
        boolean buoyant, boolean gravity, float baseBuoyancy, Set<String> liquids,
        boolean outOfControl, boolean leashed, Vec3d seat) {
    public static final float ORIGIN_HEIGHT = 0.375F;
    public static final PlayerDimensionsState DIMENSIONS = new PlayerDimensionsState(1.375F, 0.5625F);

    public BedrockBoatProperties { liquids = Set.copyOf(liquids); }

    public static BedrockBoatProperties initial(long runtimeId) {
        return new BedrockBoatProperties(runtimeId, DIMENSIONS, true, true, 1.0F,
                Set.of("minecraft:water", "minecraft:flowing_water"), false, false, new Vec3d(0, 0.870010F, 0));
    }
}
