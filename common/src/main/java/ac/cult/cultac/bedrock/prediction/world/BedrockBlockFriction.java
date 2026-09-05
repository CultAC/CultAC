package ac.cult.cultac.bedrock.prediction.world;

import java.util.Objects;

public final class BedrockBlockFriction {
    public static final double DEFAULT = 0.6F;
    public static final double ICE = 0.98F;
    public static final double BLUE_ICE = 0.989F;
    public static final double SLIME = 0.8F;
    public static final double HONEY = 0.8F;

    private BedrockBlockFriction() {
    }

    public static double from(PlacedBlockCollision block) {
        Objects.requireNonNull(block, "block");
        Object explicit = BedrockBlockMetadata.value(block.bedrockState(), BedrockBlockMetadata.BLOCK_FRICTION);
        if (explicit != null) {
            return explicitFriction(explicit);
        }

        String javaIdentifier = baseIdentifier(block.javaState());
        String bedrockId = baseIdentifier(block.bedrockIdentifier());
        if (isBlueIce(javaIdentifier) || isBlueIce(bedrockId)) {
            return BLUE_ICE;
        }
        if (isIce(javaIdentifier) || isIce(bedrockId)) {
            return ICE;
        }
        if (isSlime(javaIdentifier) || isSlime(bedrockId)) {
            return SLIME;
        }
        if (isHoney(javaIdentifier) || isHoney(bedrockId)) {
            return HONEY;
        }
        return DEFAULT;
    }

    private static double explicitFriction(Object value) {
        double friction;
        if (value instanceof Number number) {
            friction = number.doubleValue();
        } else if (value instanceof String text) {
            friction = Double.parseDouble(text);
        } else {
            throw new IllegalArgumentException(BedrockBlockMetadata.BLOCK_FRICTION
                + " must be a number or numeric string");
        }
        if (!Double.isFinite(friction) || friction <= 0.0D) {
            throw new IllegalArgumentException(BedrockBlockMetadata.BLOCK_FRICTION
                + " must be finite and positive");
        }
        return friction;
    }

    private static boolean isBlueIce(String identifier) {
        return "minecraft:blue_ice".equals(identifier) || "blue_ice".equals(identifier);
    }

    private static boolean isIce(String identifier) {
        return "minecraft:ice".equals(identifier)
            || "ice".equals(identifier)
            || "minecraft:packed_ice".equals(identifier)
            || "packed_ice".equals(identifier)
            || "minecraft:frosted_ice".equals(identifier)
            || "frosted_ice".equals(identifier);
    }

    private static boolean isSlime(String identifier) {
        return "minecraft:slime_block".equals(identifier)
            || "slime_block".equals(identifier)
            || "minecraft:slime".equals(identifier)
            || "slime".equals(identifier);
    }

    private static boolean isHoney(String identifier) {
        return "minecraft:honey_block".equals(identifier) || "honey_block".equals(identifier);
    }

    private static String baseIdentifier(String stateOrIdentifier) {
        if (stateOrIdentifier == null) {
            return "";
        }
        int bracket = stateOrIdentifier.indexOf('[');
        return bracket < 0 ? stateOrIdentifier : stateOrIdentifier.substring(0, bracket);
    }
}
