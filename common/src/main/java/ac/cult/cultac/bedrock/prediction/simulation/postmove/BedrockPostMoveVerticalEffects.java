package ac.cult.cultac.bedrock.prediction.simulation.postmove;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockAerialMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMath;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockLiquidVerticalMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockWaterTravelMovement;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockFlyingTravelMovement;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;

final class BedrockPostMoveVerticalEffects {
    private BedrockPostMoveVerticalEffects() {
    }

    static DragResult applyLiquidDrag(
        BedrockPostMoveContext input,
        BedrockMovementContext effectContext,
        Vec3d velocity,
        boolean includeVerticalDrag
    ) {
        return switch (medium(input, effectContext)) {
            case WATER -> waterDrag(input, effectContext, velocity, includeVerticalDrag);
            case LAVA -> lavaDrag(input, velocity, includeVerticalDrag);
            case AIR, NONE -> new DragResult(velocity, input.horizontalFriction());
        };
    }

    static Vec3d applyLevitation(
        BedrockPostMoveContext input,
        BedrockMovementContext effectContext,
        Vec3d velocity
    ) {
        if (medium(input, effectContext) != EffectMedium.AIR || input.effectState().levitationLevel() <= 0) {
            return velocity;
        }
        return new Vec3d(
            velocity.x(),
            BedrockAerialMovement.levitationVelocityBeforeVerticalDrag(
                input.startingVelocity().y(),
                input.effectState().levitationLevel()
            ),
            velocity.z()
        );
    }

    static Vec3d applyGravity(
        BedrockPostMoveContext input,
        BedrockMovementContext effectContext,
        Vec3d velocity
    ) {
        if (input.playerFlying()) {
            return velocity;
        }
        return switch (medium(input, effectContext)) {
            case LAVA -> lavaGravity(velocity);
            case AIR -> input.effectState().levitationLevel() > 0 ? velocity : airGravity(input, velocity);
            case WATER, NONE -> velocity;
        };
    }

    static Vec3d applyVerticalDrag(
        BedrockPostMoveContext input,
        BedrockMovementContext effectContext,
        Vec3d velocity
    ) {
        if (input.playerFlying()) {
            return BedrockFlyingTravelMovement.applyVerticalDrag(velocity);
        }
        return switch (medium(input, effectContext)) {
            case AIR -> airVerticalDrag(velocity);
            case WATER, LAVA, NONE -> velocity;
        };
    }

    static Vec3d applyPlayerWaterGravity(
        BedrockPostMoveContext input,
        BedrockMovementContext effectContext,
        Vec3d velocity
    ) {
        if (input.playerFlying()) {
            return velocity;
        }
        return switch (medium(input, effectContext)) {
            case WATER -> waterGravity(velocity, input.swimmingActorStateAfterAction());
            case LAVA, AIR, NONE -> velocity;
        };
    }

    static Vec3d applyNormalFriction(
        BedrockPostMoveContext input,
        BedrockMovementContext effectContext,
        Vec3d velocity,
        double horizontalFriction
    ) {
        return switch (medium(input, effectContext)) {
            case WATER, LAVA -> velocity;
            case AIR, NONE -> horizontalDrag(velocity, horizontalFriction);
        };
    }

    private static DragResult waterDrag(
        BedrockPostMoveContext input,
        BedrockMovementContext effectContext,
        Vec3d velocity,
        boolean includeVerticalDrag
    ) {
        double horizontalFriction = input.waterDragHorizontalFriction(effectContext);
        return new DragResult(
            new Vec3d(
                BedrockMath.f(velocity.x() * horizontalFriction),
                includeVerticalDrag ? velocity.y() * BedrockWaterTravelMovement.FRICTION : velocity.y(),
                BedrockMath.f(velocity.z() * horizontalFriction)
            ),
            horizontalFriction
        );
    }

    private static DragResult lavaDrag(
        BedrockPostMoveContext input,
        Vec3d velocity,
        boolean includeVerticalDrag
    ) {
        double horizontalFriction = input.horizontalFriction();
        return new DragResult(
            new Vec3d(
                BedrockMath.f(velocity.x() * horizontalFriction),
                includeVerticalDrag ? BedrockLiquidVerticalMovement.lavaDraggedVelocityY(velocity.y()) : velocity.y(),
                BedrockMath.f(velocity.z() * horizontalFriction)
            ),
            horizontalFriction
        );
    }

    private static Vec3d waterGravity(
        Vec3d velocity,
        boolean swimmingActorStateAfterActions
    ) {
        boolean waterGravityApplies = !swimmingActorStateAfterActions;
        return new Vec3d(
            velocity.x(),
            waterGravityApplies ? BedrockLiquidVerticalMovement.applyWaterGravity(velocity.y()) : velocity.y(),
            velocity.z()
        );
    }

    private static Vec3d lavaGravity(Vec3d velocity) {
        return new Vec3d(
            velocity.x(),
            BedrockLiquidVerticalMovement.applyLavaGravity(velocity.y()),
            velocity.z()
        );
    }

    private static Vec3d horizontalDrag(
        Vec3d velocity,
        double horizontalFriction
    ) {
        return new Vec3d(
            BedrockMath.f(velocity.x() * horizontalFriction),
            velocity.y(),
            BedrockMath.f(velocity.z() * horizontalFriction)
        );
    }

    private static Vec3d airGravity(
        BedrockPostMoveContext input,
        Vec3d velocity
    ) {
        return new Vec3d(
            velocity.x(),
            (float) velocity.y() - (float) BedrockAerialMovement.airGravity(
                input.startingVelocity(),
                input.effectState().slowFalling()
            ),
            velocity.z()
        );
    }

    private static Vec3d airVerticalDrag(Vec3d velocity) {
        return new Vec3d(
            velocity.x(),
            BedrockAerialMovement.airDraggedVelocityWithoutGravity(velocity.y()),
            velocity.z()
        );
    }

    private static EffectMedium medium(BedrockPostMoveContext input, BedrockMovementContext effectContext) {
        if (input.playerFlying()) {
            return EffectMedium.AIR;
        }
        Medium liquidMovementMedium = effectContext.liquidMovementMedium();
        if (input.inWater()) {
            return EffectMedium.WATER;
        }
        boolean lavaTravelEffects = input.inLava()
            || input.lavaSwimUpApplied()
            || effectContext.inLava()
            || liquidMovementMedium == Medium.LAVA;
        if (!lavaTravelEffects) {
            return EffectMedium.AIR;
        }
        return input.navigationCanWalkInLava() ? EffectMedium.NONE : EffectMedium.LAVA;
    }

    enum EffectMedium {
        WATER,
        LAVA,
        AIR,
        NONE
    }

    record DragResult(Vec3d velocity, double horizontalFriction) {
    }
}
