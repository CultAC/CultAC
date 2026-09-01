package ac.grim.grimac.bedrock.prediction.simulation.travel;

import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import ac.grim.grimac.bedrock.prediction.model.AttributeState;
import ac.grim.grimac.bedrock.prediction.model.BedrockEffectState;
import ac.grim.grimac.bedrock.prediction.model.EquipmentState;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.bedrock.prediction.world.StandingSurfaceState;
import ac.grim.grimac.bedrock.prediction.world.Surface;

final class BedrockHorizontalSpeedControl {
    private static final double SOUL_SPEED_BASE_BOOST = 0.03D;
    private static final double SOUL_SPEED_BOOST_PER_LEVEL_SCALE = 0.35D;
    private static final double SOUL_SPEED_MOVING_GATE_THRESHOLD = 0.001D;
    private static final double DRY_AIR_TRAVEL_SPEED = 0.019999999552965164D;
    private static final float SOUL_SAND_NO_SOUL_SPEED_FRICTION_MULTIPLIER = 1.225F;

    private BedrockHorizontalSpeedControl() {
    }

    static double baseMovementSpeed(
        boolean horizontalAttributeSpeed,
        boolean dryAirTravelSpeed,
        double baseMovementSpeed
    ) {
        if (horizontalAttributeSpeed) {
            return baseMovementSpeed;
        }
        return dryAirTravelSpeed
            ? DRY_AIR_TRAVEL_SPEED
            : AttributeState.DEFAULT_BASE_MOVEMENT_SPEED;
    }

    static PreparedSpeed prepare(
        BedrockMovementState current,
        BedrockInputFrame frame,
        AttributeState attributeState,
        EquipmentState equipmentState,
        BedrockEffectState effectState,
        StandingSurfaceState standingSurfaceState,
        boolean ordinaryAirborne,
        boolean inWater,
        boolean inLava
    ) {
        int soulSpeedLevel = equipmentState.soulSpeedLevel();
        boolean horizontalAttributeSpeed = !ordinaryAirborne && !inLava;
        boolean dryAirTravelSpeed = !horizontalAttributeSpeed && !inWater && !inLava;
        double movementSpeed = baseMovementSpeed(
            horizontalAttributeSpeed,
            dryAirTravelSpeed,
            attributeState.baseMovementSpeed()
        );
        double inputRadiusMovementSpeed = inputRadiusMovementSpeed(
            horizontalAttributeSpeed,
            dryAirTravelSpeed,
            attributeState
        );
        movementSpeed = speedWithSoulSpeedBoost(
            movementSpeed,
            ordinaryAirborne,
            inWater,
            inLava,
            soulSpeedLevel,
            standingSurfaceState,
            current.lastPhysicalDisplacementSquared()
        );
        inputRadiusMovementSpeed = speedWithSoulSpeedBoost(
            inputRadiusMovementSpeed,
            ordinaryAirborne,
            inWater,
            inLava,
            soulSpeedLevel,
            standingSurfaceState,
            current.lastPhysicalDisplacementSquared()
        );
        float speed = finalSpeed(
            movementSpeed,
            horizontalAttributeSpeed,
            effectState
        );
        float inputRadiusSpeed = finalSpeed(
            inputRadiusMovementSpeed,
            horizontalAttributeSpeed,
            effectState
        );
        return new PreparedSpeed(
            speed,
            inputRadiusSpeed,
            horizontalAttributeSpeed,
            dryAirTravelSpeed,
            soulSpeedLevel > 0);
    }

    private static double inputRadiusMovementSpeed(
        boolean horizontalAttributeSpeed,
        boolean dryAirTravelSpeed,
        AttributeState attributeState
    ) {
        if (horizontalAttributeSpeed) {
            return attributeState.horizontalInputBaseMovementSpeed();
        }
        return dryAirTravelSpeed
            ? DRY_AIR_TRAVEL_SPEED
            : AttributeState.DEFAULT_BASE_MOVEMENT_SPEED;
    }

    static double speedWithSoulSpeedBoost(
        double movementSpeed,
        boolean ordinaryAirborne,
        boolean inWater,
        boolean inLava,
        int soulSpeedLevel,
        StandingSurfaceState standingSurfaceState,
        double lastPhysicalDisplacementSquared
    ) {
        if (!ordinaryAirborne
            && !inWater
            && !inLava
            && soulSpeedLevel > 0
            && soulSpeedModifierActive(standingSurfaceState, lastPhysicalDisplacementSquared)) {
            return movementSpeed + soulSpeedAttributeBoost(soulSpeedLevel);
        }
        return movementSpeed;
    }

    static float finalSpeed(
        double movementSpeed,
        boolean horizontalAttributeSpeed,
        BedrockEffectState effectState
    ) {
        float speed = (float) movementSpeed;
        if (horizontalAttributeSpeed) {
            speed = effectState.applyMovementSpeedEffects(speed);
        }
        return speed;
    }

    static float flyingSpeed(double movementAbilityFlySpeed, double horizontalFlySpeedScale) {
        return (float) (movementAbilityFlySpeed * horizontalFlySpeedScale);
    }

    static float soulSandGroundControl(
        boolean soulSpeedEnchantFlagPresent,
        double groundControl,
        double groundFriction
    ) {
        if (soulSpeedEnchantFlagPresent) {
            return (float) groundControl;
        }
        float defaultGroundFriction = (float) groundFriction;
        float soulSandGroundFriction = defaultGroundFriction * SOUL_SAND_NO_SOUL_SPEED_FRICTION_MULTIPLIER;
        float scale = defaultGroundFriction / soulSandGroundFriction;
        return (float) groundControl * scale * scale * scale;
    }

    private static double soulSpeedAttributeBoost(int level) {
        return (level * SOUL_SPEED_BOOST_PER_LEVEL_SCALE + 1.0D) * SOUL_SPEED_BASE_BOOST;
    }

    private static boolean soulSpeedModifierActive(
        StandingSurfaceState standingSurfaceState,
        double lastPhysicalDisplacementSquared
    ) {
        if (!standingSurfaceState.hasSurface(Surface.SOUL_SAND)
            && !standingSurfaceState.hasSurface(Surface.SOUL_SOIL)) {
            return false;
        }
        if (!standingSurfaceState.hasSurface(Surface.SOUL_SOIL)) {
            return true;
        }
        return lastPhysicalDisplacementSquared > SOUL_SPEED_MOVING_GATE_THRESHOLD;
    }

    record PreparedSpeed(
        float speed,
        float inputRadiusSpeed,
        boolean horizontalAttributeSpeed,
        boolean dryAirTravelSpeed,
        boolean soulSpeedEnchantFlagPresent
    ) {
    }
}
