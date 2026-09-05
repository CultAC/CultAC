package ac.cult.cultac.bedrock.prediction.world;

import java.util.Map;
import java.util.Objects;

public final class BedrockBlockMetadata {
    public static final String BLOCK_PROPERTY_MASK = "blockPropertyMask";
    public static final String CONTACT_BEHAVIORS = "contactBehaviors";
    public static final String BLOCK_FRICTION = "blockFriction";

    private BedrockBlockMetadata() {
    }

    public static boolean hasBedrockBlockProperty(PlacedBlockCollision block, long mask) {
        Objects.requireNonNull(block, "block");
        Object value = block.bedrockState().get(BLOCK_PROPERTY_MASK);
        if (value == null) {
            return false;
        }
        return (blockPropertyMask(value) & mask) != 0L;
    }

    private static long blockPropertyMask(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            return Long.decode(text);
        }
        throw new IllegalArgumentException(
            BLOCK_PROPERTY_MASK + " must be a number or numeric string"
        );
    }

    public static Object value(Map<String, Object> bedrockState, String key) {
        return bedrockState == null ? null : bedrockState.get(key);
    }
}
