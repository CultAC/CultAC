package ac.cult.cultac.bedrock.prediction.model;

public record MovementModifierState(
    boolean elytraGlideAvailable,
    boolean movementAbilityMayFly,
    boolean movementAbilityFlying,
    double movementAbilityFlySpeed,
    boolean navigationCanWalkInLava,
    boolean riptideAvailable,
    boolean rainContact,
    boolean itemUseSlowdownActive,
    double itemUseMovementModifier,
    long itemUseSlowdownDurationTicks
) {
    private static final double DEFAULT_ITEM_USE_MOVEMENT_MODIFIER = 0.35D;
    private static final double DEFAULT_MOVEMENT_ABILITY_FLY_SPEED = 0.05D;
    public static final MovementModifierState NONE =
        new MovementModifierState(
            false,
            false,
            false,
            DEFAULT_MOVEMENT_ABILITY_FLY_SPEED,
            false,
            false,
            false,
            false,
            DEFAULT_ITEM_USE_MOVEMENT_MODIFIER,
            0L
        );

    public MovementModifierState {
        if (movementAbilityFlying && !movementAbilityMayFly) {
            throw new IllegalArgumentException("movementAbilityFlying requires movementAbilityMayFly");
        }
        if (!Double.isFinite(movementAbilityFlySpeed) || movementAbilityFlySpeed < 0.0D) {
            throw new IllegalArgumentException("movement ability fly speed must be finite and non-negative");
        }
        if (!Double.isFinite(itemUseMovementModifier) || itemUseMovementModifier < 0.0D) {
            throw new IllegalArgumentException("item use movement modifier must be finite and non-negative");
        }
        if (itemUseSlowdownDurationTicks < 0L) {
            throw new IllegalArgumentException("item use slowdown duration ticks must be non-negative");
        }
    }
}
