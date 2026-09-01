package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import ac.grim.grimac.bedrock.prediction.input.BedrockInputIntent;
import ac.grim.grimac.bedrock.prediction.model.BedrockEffectState;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;

public final class BedrockTravelInputControl {
    private static final float DEFAULT_SNEAK_MOVE_SCALE = 0.3F;

    private BedrockTravelInputControl() {
    }

    public static InputControlState resolve(
        BedrockMovementState current,
        BedrockInputFrame frame,
        boolean actorSprinting,
        BedrockTravelOptions.SprintTravelSpeedMode sprintTravelSpeedMode,
        BedrockInputIntent intent,
        BedrockMovementContext context,
        BedrockEffectState effectState
    ) {
        return resolve(
            current,
            frame,
            actorSprinting,
            sprintTravelSpeedMode,
            BedrockTravelOptions.SprintJumpImpulseMode.ORDERED_ACTOR_FLAG,
            intent,
            context,
            effectState
        );
    }

    public static InputControlState resolve(
        BedrockMovementState current,
        BedrockInputFrame frame,
        boolean actorSprinting,
        BedrockTravelOptions.SprintTravelSpeedMode sprintTravelSpeedMode,
        BedrockTravelOptions.SprintJumpImpulseMode sprintJumpImpulseMode,
        BedrockInputIntent intent,
        BedrockMovementContext context,
        BedrockEffectState effectState
    ) {
        ItemUseSlowdown itemUseSlowdown = itemUseSlowdown(current, intent, context);
        boolean controlsNotSuppressed = !effectState.blindness() && !itemUseSlowdown.active();

        boolean sprintJumpImpulseActive = sprintJumpImpulseMode.actorSprinting(actorSprinting) && controlsNotSuppressed;
        boolean sprintSpeedInput = sprintTravelSpeedMode.sprintSpeedInput(actorSprinting) && controlsNotSuppressed;
        float moveInputScale = itemUseSlowdown.moveInputScale(context) * sneakMoveScale(current, frame, intent, context);
        return new InputControlState(
            moveInputScale,
            itemUseSlowdown.active(),
            itemUseSlowdown.ticks(),
            sprintJumpImpulseActive,
            sprintSpeedInput
        );
    }

    private static float sneakMoveScale(
        BedrockMovementState current,
        BedrockInputFrame frame,
        BedrockInputIntent intent,
        BedrockMovementContext context
    ) {

        return !context.inWater()
            && !intent.pose().stopSwimming()
            && actualSneakingMovement(frame, current)
            ? DEFAULT_SNEAK_MOVE_SCALE
            : 1.0F;
    }

    private static boolean actualSneakingMovement(BedrockInputFrame frame, BedrockMovementState current) {
        return current.sneakingTicks() > 0L
            || frame.inputData().contains("SNEAKING")
            || frame.inputData().contains("SNEAK_CURRENT_RAW")
            || frame.inputData().contains("START_SNEAKING")
            || frame.inputData().contains("SNEAK_PRESSED_RAW")
            || frame.inputData().contains("SNEAK_TOGGLE_DOWN");
    }

    private static ItemUseSlowdown itemUseSlowdown(
        BedrockMovementState current,
        BedrockInputIntent intent,
        BedrockMovementContext context
    ) {
        boolean itemUseSlowdownActive = context.itemUseSlowdownActive()
            && !intent.itemUse().release()
            && !intent.itemUse().stop()
            && (intent.itemUse().start() || current.itemUseSlowdownActive());
        long itemUseSlowdownTicks = 0L;
        if (itemUseSlowdownActive) {
            itemUseSlowdownTicks = intent.itemUse().start()
                ? 1L
                : current.itemUseSlowdownTicks() + 1L;
            long durationTicks = context.itemUseSlowdownDurationTicks();
            if (durationTicks > 0L && itemUseSlowdownTicks > durationTicks) {
                itemUseSlowdownActive = false;
                itemUseSlowdownTicks = 0L;
            }
        }
        return new ItemUseSlowdown(itemUseSlowdownActive, itemUseSlowdownTicks);
    }

    public record InputControlState(
        float moveInputScale,
        boolean itemUseSlowdownActive,
        long itemUseSlowdownTicks,
        boolean sprintJumpImpulseActive,
        boolean sprintSpeedInput
    ) {
    }

    private record ItemUseSlowdown(
        boolean active,
        long ticks
    ) {
        float moveInputScale(BedrockMovementContext context) {
            if (!active) {
                return 1.0F;
            }
            double itemUseMoveScale = context.itemUseMovementModifier() * context.itemUseMovementModifier();
            return (float) itemUseMoveScale;
        }
    }
}
