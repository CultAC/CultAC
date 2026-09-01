package ac.grim.grimac.bedrock.prediction.simulation.travel;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import ac.grim.grimac.bedrock.prediction.input.BedrockInputIntent;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;

public final class BedrockFlyingTravelMovement {
    private static final double VERTICAL_FLY_SPEED = 0.20000000298023224D;
    private static final double HORIZONTAL_FLY_SPEED_SCALE = 2.0D;
    private static final double PLAYER_FLYING_VERTICAL_DRAG = 0.6D;

    private BedrockFlyingTravelMovement() {
    }

    public static BedrockTravelHorizontalControl.Step speed(
        BedrockMovementContext context,
        float moveInputScale
    ) {
        float flySpeed = BedrockHorizontalSpeedControl.flyingSpeed(
            context.movementAbilityFlySpeed(),
            HORIZONTAL_FLY_SPEED_SCALE
        );
        return new BedrockTravelHorizontalControl.Step(flySpeed * moveInputScale, 1.0D);
    }

    public static Vec3d applyVerticalInput(
        Vec3d velocity,
        BedrockInputFrame frame,
        BedrockInputIntent intent,
        boolean descendInput
    ) {
        return new Vec3d(
            velocity.x(),
            verticalInput(frame, intent, descendInput),
            velocity.z()
        );
    }

    public static Vec3d applyVerticalDrag(Vec3d velocity) {
        return new Vec3d(
            velocity.x(),
            velocity.y() * PLAYER_FLYING_VERTICAL_DRAG,
            velocity.z()
        );
    }

    private static double verticalInput(
        BedrockInputFrame frame,
        BedrockInputIntent intent,
        boolean descendInput
    ) {
        if (intent.vertical().upwardInput(frame.jumping())) {
            return VERTICAL_FLY_SPEED;
        }
        return descendInput ? -VERTICAL_FLY_SPEED : 0.0D;
    }
}
