package ac.grim.grimac.bedrock.prediction.simulation.travel;

import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import ac.grim.grimac.bedrock.prediction.model.BedrockEffectState;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockClimbState;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockLiquidVerticalMovement;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockWaterTravelMovement;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.bedrock.prediction.world.BedrockBlockFriction;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;
import ac.grim.grimac.bedrock.prediction.world.StandingSurfaceState;
import ac.grim.grimac.bedrock.prediction.world.Surface;

public final class BedrockTravelHorizontalControl {
    private static final double DEFAULT_BLOCK_FRICTION = BedrockBlockFriction.DEFAULT;
    private static final double GROUND_CONTROL_NUMERATOR = 0.21600002D;
    private static final double DRY_AIR_TRAVEL_SPEED = 0.019999999552965164D;
    private static final double INPUT_FRICTION = 0.98D;
    private static final double GROUND_CONTROL = GROUND_CONTROL_NUMERATOR
        / (DEFAULT_BLOCK_FRICTION * DEFAULT_BLOCK_FRICTION * DEFAULT_BLOCK_FRICTION);
    private static final double AIR_CONTROL = DRY_AIR_TRAVEL_SPEED * INPUT_FRICTION;
    public static final double AIR_FRICTION = 0.91D;
    private static final double GROUND_FRICTION = DEFAULT_BLOCK_FRICTION * AIR_FRICTION;

    private static final double SPRINTING_SPEED_ATTRIBUTE_MODIFIER = 0.3D;
    private static final double SPRINT_MOVEMENT_SPEED_MULTIPLIER = 1.0D + SPRINTING_SPEED_ATTRIBUTE_MODIFIER;

    private BedrockTravelHorizontalControl() {
    }

    public static Step resolveWaterTravel(
        BedrockMovementState current,
        BedrockInputFrame frame,
        BedrockMovementContext context,
        BedrockEffectState effectState,
        StandingSurfaceState standingSurfaceState,
        boolean sprintSpeedInput,
        float moveInputScale
    ) {
        double swimSpeedMultiplier = context.worldState().fluidState().swimSpeedMultiplier();
        BedrockHorizontalSpeedControl.PreparedSpeed preparedSpeed = BedrockHorizontalSpeedControl.prepare(
            current,
            frame,
            context.attributeState(),
            context.equipmentState(),
            effectState,
            standingSurfaceState,
            false,
            true,
            false
        );
        double horizontalInputLimit = scaledInputLimit(
            BedrockWaterTravelMovement.travelScale(
                preparedSpeed.speed(),
                context.attributeState().underwaterMovementSpeed(),
                context.equipmentState().depthStriderLevel(),
                context.worldState().fluidState().waterWalkOnGroundComponentPresent(),
                swimSpeedMultiplier
            ),
            moveInputScale,
            false
        );
        double horizontalFriction = BedrockWaterTravelMovement.horizontalDrag(
            sprintSpeedInput,
            context.equipmentState().depthStriderLevel(),
            context.worldState().fluidState().waterWalkOnGroundComponentPresent(),
            swimSpeedMultiplier,
            GROUND_FRICTION
        );
        return new Step(horizontalInputLimit, horizontalFriction);
    }

    public static Step resolveLavaTravel(
        BedrockMovementContext context,
        StandingSurfaceState standingSurfaceState,
        boolean navigationCanWalkInLava,
        float moveInputScale,
        boolean onGroundTravel
    ) {
        double horizontalInputLimit = scaledInputLimit(
            lavaTravelScale(context.attributeState().lavaMovementSpeed()),
            moveInputScale,

            false
        );
        double horizontalFriction = navigationCanWalkInLava
            ? groundOrAirFriction(standingSurfaceState, onGroundTravel)
            // The vanilla lava-drag system applies 0.5 to the full velocity
            // for lava-travel entities unless navigation can walk in lava.
            : BedrockLiquidVerticalMovement.LAVA_FRICTION;
        return new Step(horizontalInputLimit, horizontalFriction);
    }

    public static Step resolveNormalTravel(
        BedrockMovementState current,
        BedrockInputFrame frame,
        BedrockMovementContext context,
        BedrockEffectState effectState,
        StandingSurfaceState standingSurfaceState,
        BedrockClimbState climb,
        boolean inPowderSnow,
        boolean sprintSpeedInput,
        float moveInputScale,
        boolean onGroundTravel
    ) {
        BedrockHorizontalSpeedControl.PreparedSpeed preparedSpeed = BedrockHorizontalSpeedControl.prepare(
            current,
            frame,
            context.attributeState(),
            context.equipmentState(),
            effectState,
            standingSurfaceState,

            !onGroundTravel,
            false,
            false
        );
        float control = normalTravelControl(
            standingSurfaceState,
            climb,
            onGroundTravel,
            inPowderSnow,
            preparedSpeed
        );
        double horizontalInputLimit = scaledInputLimit(
            preparedSpeed.inputRadiusSpeed() * control,
            moveInputScale,
            sprintSpeedInput
        );
        double horizontalFriction = groundOrAirFriction(standingSurfaceState, onGroundTravel)
            * context.attributeState().frictionModifier();
        return new Step(horizontalInputLimit, horizontalFriction);
    }

    private static float normalTravelControl(
        StandingSurfaceState standingSurfaceState,
        BedrockClimbState climb,
        boolean onGroundTravel,
        boolean inPowderSnow,
        BedrockHorizontalSpeedControl.PreparedSpeed preparedSpeed
    ) {
        double standingBlockFriction = standingSurfaceState.blockFriction();
        double standingGroundFriction = standingBlockFriction * AIR_FRICTION;
        float standingGroundControl = groundControl(standingBlockFriction);
        boolean onSoulSand = standingSurfaceState.hasSurface(Surface.SOUL_SAND);
        return (float) (preparedSpeed.dryAirTravelSpeed() ? GROUND_CONTROL
            : onSoulSand && onGroundTravel
            ? BedrockHorizontalSpeedControl.soulSandGroundControl(
                preparedSpeed.soulSpeedEnchantFlagPresent(),
                standingGroundControl,
                standingGroundFriction
            )
            : inPowderSnow && onGroundTravel ? GROUND_CONTROL
            : climb.climbable().horizontalControl() ? GROUND_CONTROL
            : onGroundTravel ? standingGroundControl
            : AIR_CONTROL);
    }

    private static double groundOrAirFriction(
        StandingSurfaceState standingSurfaceState,
        boolean onGroundTravel
    ) {
        return onGroundTravel
            ? standingSurfaceState.blockFriction() * AIR_FRICTION
            : AIR_FRICTION;
    }

    public static double waterHorizontalDrag(
        BedrockMovementContext context,
        boolean sprintingWaterDrag
    ) {
        return BedrockWaterTravelMovement.horizontalDrag(
            sprintingWaterDrag,
            context.equipmentState().depthStriderLevel(),
            context.worldState().fluidState().waterWalkOnGroundComponentPresent(),
            context.worldState().fluidState().swimSpeedMultiplier(),
            GROUND_FRICTION
        );
    }

    public static double dryAirHorizontalInputLimit(
        boolean sprintSpeedInput,
        float moveInputScale
    ) {
        return scaledInputLimit(DRY_AIR_TRAVEL_SPEED * GROUND_CONTROL, moveInputScale, sprintSpeedInput);
    }

    private static double scaledInputLimit(
        double baseLimit,
        float moveInputScale,
        boolean sprintSpeedInput
    ) {
        double horizontalInputLimit = baseLimit * moveInputScale;
        return sprintSpeedInput ? horizontalInputLimit * SPRINT_MOVEMENT_SPEED_MULTIPLIER : horizontalInputLimit;
    }

    public static float lavaTravelScale(float lavaMovementSpeed) {

        return lavaMovementSpeed;
    }

    private static float groundControl(double blockFriction) {
        return (float) (GROUND_CONTROL_NUMERATOR / (blockFriction * blockFriction * blockFriction));
    }

    public record Step(
        double horizontalInputLimit,
        double horizontalFriction
    ) {
    }
}
