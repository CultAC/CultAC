package ac.cult.cultac.bedrock.prediction.simulation.travel;

import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;

final class BedrockHorizontalSpeedControl {
    private static final float PLAYER_AIR_TRAVEL_SPEED = 0.02F;
    private static final float SOUL_SAND_NO_SOUL_SPEED_FRICTION_MULTIPLIER = 1.225F;

    private BedrockHorizontalSpeedControl() {}

    static double baseMovementSpeed(
            boolean horizontalAttributeSpeed,
            boolean dryAirTravelSpeed,
            double baseMovementSpeed,
            float airMovementSpeed) {
        if (horizontalAttributeSpeed) {
            return baseMovementSpeed;
        }
        return dryAirTravelSpeed ? airMovementSpeed : AttributeState.DEFAULT_BASE_MOVEMENT_SPEED;
    }

    static PreparedSpeed prepare(
            BedrockMovementState current,
            BedrockInputFrame frame,
            AttributeState attributeState,
            EquipmentState equipmentState,
            boolean ordinaryAirborne,
            boolean inWater,
            boolean inLava) {
        boolean horizontalAttributeSpeed = !ordinaryAirborne && !inLava;
        boolean dryAirTravelSpeed = !horizontalAttributeSpeed && !inWater && !inLava;
        float airMovementSpeed =
                current.isHorse() ? (float) attributeState.baseMovementSpeed() * 0.1F : PLAYER_AIR_TRAVEL_SPEED;
        double movementSpeed = baseMovementSpeed(
                horizontalAttributeSpeed, dryAirTravelSpeed, attributeState.baseMovementSpeed(), airMovementSpeed);
        double inputRadiusMovementSpeed = baseMovementSpeed(
                horizontalAttributeSpeed,
                dryAirTravelSpeed,
                attributeState.horizontalInputBaseMovementSpeed(),
                airMovementSpeed);
        // These are compensated attribute current values, including Speed/Slowness and the soul
        // speed boost. Geyser LivingEntity#calculateAttribute applies the Java modifiers before
        // sending minecraft:movement. Travel consumes that current value directly
        // and must not apply the effect levels or the enchantment a second time.
        float speed = (float) movementSpeed;
        float inputRadiusSpeed = (float) inputRadiusMovementSpeed;
        return new PreparedSpeed(
                speed,
                inputRadiusSpeed,
                horizontalAttributeSpeed,
                dryAirTravelSpeed,
                equipmentState.soulSpeedLevel() > 0);
    }

    static float flyingSpeed(double movementAbilityFlySpeed, double horizontalFlySpeedScale) {
        return (float) (movementAbilityFlySpeed * horizontalFlySpeedScale);
    }

    static float soulSandGroundControl(
            boolean soulSpeedEnchantFlagPresent, double groundControl, double groundFriction) {
        if (soulSpeedEnchantFlagPresent) {
            return (float) groundControl;
        }
        float defaultGroundFriction = (float) groundFriction;
        float soulSandGroundFriction = defaultGroundFriction * SOUL_SAND_NO_SOUL_SPEED_FRICTION_MULTIPLIER;
        float scale = defaultGroundFriction / soulSandGroundFriction;
        return (float) groundControl * scale * scale * scale;
    }

    record PreparedSpeed(
            float speed,
            float inputRadiusSpeed,
            boolean horizontalAttributeSpeed,
            boolean dryAirTravelSpeed,
            boolean soulSpeedEnchantFlagPresent) {}
}
