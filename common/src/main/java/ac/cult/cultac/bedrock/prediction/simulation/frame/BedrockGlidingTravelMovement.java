package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;

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

    private static boolean shouldApplyStopGlidingAction(BedrockMovementState current, BedrockInputIntent intent) {
        return intent.glide().stopRequest()
            || (current.glidingRequest() && current.collisionFlags().onGround());
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
                shouldApplyStopGlidingAction(current, intent),
                actorStateAfterStart
            );
        }

        boolean actorStateAfterActions() {
            return actorStateAfterStart && !stopAction;
        }
    }

}
