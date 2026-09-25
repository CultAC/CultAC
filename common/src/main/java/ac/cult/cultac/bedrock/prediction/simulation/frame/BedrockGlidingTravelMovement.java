package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;

final class BedrockGlidingTravelMovement {
    private BedrockGlidingTravelMovement() {
    }

    static BedrockGlideState resolve(
        BedrockMovementState current,
        BedrockInputIntent intent,
        BedrockMovementContext context
    ) {
        GlideActionState actions = GlideActionState.resolve(current, intent, context);

        boolean activeAfterActions = actions.actorStateAfterActions();
        boolean requestAfterActions = requestAfterActions(current.glidingRequest(), actions);
        boolean liquidTravel = context.inWater() || context.inLava();
        return new BedrockGlideState(
            activeAfterActions,
            activeAfterActions && !liquidTravel,
            activeAfterActions,
            requestAfterActions
        );
    }

    static boolean requestsResize(BedrockMovementState current, BedrockInputIntent intent,
                                  BedrockMovementContext context) {
        GlideActionState actions = GlideActionState.resolve(current, intent, context);
        return actions.startIntent() || actions.stopAction();
    }

    private static boolean shouldApplyStartGlidingIntent(
        BedrockMovementState current,
        BedrockInputIntent intent,
        BedrockMovementContext context
    ) {
        return (intent.glide().start() || intent.glide().startAction())
            && context.elytraGlideAvailable()
            && !context.movementAbilityFlying()
            && !current.collisionFlags().onGround();
    }

    private static boolean shouldApplyStopGlidingAction(
        BedrockMovementState current, BedrockInputIntent intent, BedrockMovementContext context
    ) {
        // Replays can stop gliding at a different tick than the original movement, we must simulate it
        return intent.glide().stopRequest()
            || (current.glidingRequest() && (
                current.collisionFlags().onGround()
                || !context.elytraGlideAvailable()
                || context.inWater()
                || context.movementAbilityFlying()
                || context.entityContactState().passenger()
                || (intent.jump().held() && !current.inputFrame().jumping()
                    && !context.movementAbilityInstabuild() && current.fallFlyTicks() > 10L)
                || canAutoClimb(current, context)));
    }

    private static boolean canAutoClimb(BedrockMovementState current, BedrockMovementContext context) {
        var feet = current.physicalFeetPosition();
        var position = new BlockPosition((int) Math.floor(feet.x()),
                (int) Math.floor(current.collisionBox().minY()), (int) Math.floor(feet.z()));
        return context.worldState().blockCollisionWorld().blockAt(position).map(block ->
                block.hasContactBehavior(BlockContactBehavior.CLIMBABLE)
                || (context.equipmentState().leatherBoots()
                    && block.hasContactBehavior(BlockContactBehavior.POWDER_SNOW))).orElse(false);
    }

    private static boolean requestAfterActions(
        boolean glidingRequestAtStart,
        GlideActionState actions
    ) {
        if (actions.stopAction()) {
            return false;
        }
        if (actions.startIntent()) {
            return true;
        }

        return glidingRequestAtStart;
    }

    private record GlideActionState(
        boolean startIntent,
        boolean stopAction,
        boolean actorStateAfterStart
    ) {
        static GlideActionState resolve(
            BedrockMovementState current,
            BedrockInputIntent intent,
            BedrockMovementContext context
        ) {
            boolean startIntent = shouldApplyStartGlidingIntent(current, intent, context);
            boolean actorStateAfterStart = current.gliding() || startIntent;
            return new GlideActionState(
                startIntent,
                shouldApplyStopGlidingAction(current, intent, context),
                actorStateAfterStart
            );
        }

        boolean actorStateAfterActions() {
            return actorStateAfterStart && !stopAction;
        }
    }

}
