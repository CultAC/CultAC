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
        boolean requestAfterActions = requestAfterActions(current.glidingRequest(), actions, activeAfterActions);
        boolean liquidTravel = context.inWater() || context.inLava();
        return new BedrockGlideState(
            activeAfterActions,
            activeAfterActions && !liquidTravel,
            activeAfterActions,
            requestAfterActions
        );
    }

    private static boolean shouldApplyStartGlidingIntent(
        BedrockMovementState current,
        BedrockInputIntent intent,
        BedrockMovementContext context
    ) {
        return intent.glide().start()
            && context.elytraGlideAvailable()
            && !context.movementAbilityFlying()
            && !current.collisionFlags().onGround();
    }

    private static boolean hasStartGlidingAction(BedrockInputIntent intent) {
        return intent.glide().startAction();
    }

    private static boolean shouldApplyStopGlidingAction(BedrockInputIntent intent) {
        return intent.glide().stopRequest();
    }

    private static boolean requestAfterActions(
        boolean glidingRequestAtStart,
        GlideActionState actions,
        boolean actorGlidingAfterActions
    ) {
        if (actions.stopAction()) {
            return false;
        }
        if (actions.startIntent()) {
            return true;
        }

        return glidingRequestAtStart || actorGlidingAfterActions;
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
            boolean startAction = startIntent || hasStartGlidingAction(intent);
            boolean actorStateAfterStart = current.gliding() || startAction;
            return new GlideActionState(
                startIntent,
                shouldApplyStopGlidingAction(intent),
                actorStateAfterStart
            );
        }

        boolean actorStateAfterActions() {
            return actorStateAfterStart && !stopAction;
        }
    }

}
