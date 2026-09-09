package ac.cult.cultac.bedrock.prediction.simulation.frame;


public final class BedrockWaterTravelMovement {
    public static final double FRICTION = 0.8D;
    private static final double SPRINTING_HORIZONTAL_DRAG = 0.9D;

    private static final int WATER_WALK_SPEED_MAX_LEVEL = 3;
    private static final double DOLPHIN_BOOST_NO_WATER_WALK_SPEED_SCALE = 0.7D;

    private BedrockWaterTravelMovement() {
    }

    public static float travelScale(
        float movementSpeed,
        float underwaterMovementSpeed,
        int depthStriderLevel,
        boolean onGround,
        double swimSpeedMultiplier
    ) {
        // Boosted swimming uses the full Depth Strider contribution while airborne.
        if (swimSpeedMultiplier > 1.0D) {
            float enchantPercent = (float) waterWalkSpeedEnchantPercent(depthStriderLevel, true);
            return underwaterMovementSpeed * (float) swimSpeedMultiplier
                * (enchantPercent * 0.3F + (float) DOLPHIN_BOOST_NO_WATER_WALK_SPEED_SCALE);
        }
        float enchantPercent = (float) waterWalkSpeedEnchantPercent(depthStriderLevel, onGround);
        float travelSpeed = underwaterMovementSpeed;
        if (enchantPercent > 0.0F) {
            travelSpeed = travelSpeed + (movementSpeed - travelSpeed) * enchantPercent;
        }
        return travelSpeed;
    }

    public static double horizontalDrag(
        boolean sprintingWaterDrag,
        int depthStriderLevel,
        boolean onGround,
        double swimSpeedMultiplier,
        double groundFriction
    ) {
        double baseDrag = sprintingWaterDrag ? SPRINTING_HORIZONTAL_DRAG : FRICTION;
        if (swimSpeedMultiplier > 1.0D) {
            return baseDrag;
        }
        double enchantPercent = waterWalkSpeedEnchantPercent(depthStriderLevel, onGround);
        return baseDrag + (groundFriction - baseDrag) * enchantPercent;
    }

    private static double waterWalkSpeedEnchantPercent(int depthStriderLevel, boolean onGround) {
        int clampedLevel = Math.max(0, Math.min(WATER_WALK_SPEED_MAX_LEVEL, depthStriderLevel));
        double percent = clampedLevel / (double) WATER_WALK_SPEED_MAX_LEVEL;
        return onGround ? percent : percent * 0.5D;
    }
}
