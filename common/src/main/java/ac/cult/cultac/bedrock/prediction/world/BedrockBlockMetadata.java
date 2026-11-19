package ac.cult.cultac.bedrock.prediction.world;

import java.util.Map;
import java.util.Objects;

public final class BedrockBlockMetadata {
    public static final String BLOCK_PROPERTY_MASK = "blockPropertyMask";
    public static final String CONTACT_BEHAVIORS = "contactBehaviors";
    public static final String BLOCK_FRICTION = "blockFriction";
    public static final String GEYSER_PASSABLE = "geyserPassable";
    /** The identifier of the block a moving block carries, which a landing on the moving block bounces off. */
    public static final String MOVING_BLOCK_CARRIED = "movingBlockCarried";
    /**
     * The one box, as its six world bounds, an actor stands on a block by, where that is neither its only collision
     * box nor, with several, its cell. Empty for a block that is no surface to stand on.
     */
    public static final String STANDING_SHAPE = "standingShape";

    private BedrockBlockMetadata() {}

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
        throw new IllegalArgumentException(BLOCK_PROPERTY_MASK + " must be a number or numeric string");
    }

    public static Object value(Map<String, Object> bedrockState, String key) {
        return bedrockState == null ? null : bedrockState.get(key);
    }
}
