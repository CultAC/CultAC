package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInput.ScaffoldingVerticalBranch;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbSurface;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbSurface.Type;

public final class BedrockClimbMovement {
    public static final double SCAFFOLDING_ASCEND_VELOCITY = 0.15F;
    public static final double SCAFFOLDING_DESCEND_VELOCITY = -0.15F;
    public static final double LADDER_ASCEND_VELOCITY = 0.2F;
    public static final double CLIMBABLE_MAX_FALL_SPEED = -0.2F;

    private BedrockClimbMovement() {
    }

    public static BedrockClimbState resolveSurface(
        BedrockClimbableContact contact,
        boolean inWater,
        boolean inLava
    ) {
        return new BedrockClimbState(
            new BedrockClimbSurface(
                contact.scaffolding() ? Type.SCAFFOLDING : (contact.climbing() ? Type.CLIMBABLE : Type.NONE),
                contact.descendAllowed(),
                inWater || inLava
            ),
            BedrockScaffoldingState.NONE,
            BedrockClimbableState.NONE
        );
    }

    public static BedrockClimbState resolveActions(
        BedrockMovementState current,
        BedrockInputFrame frame,
        BedrockInputIntent intent,
        BedrockClimbState climb,
        ScaffoldingVerticalBranch scaffoldingVerticalBranch
    ) {
        BedrockClimbSurface surface = climb.surface();
        ClimbInput input = ClimbInput.from(frame, intent);
        BedrockScaffoldingState scaffolding = resolveScaffolding(surface, input, scaffoldingVerticalBranch);
        BedrockClimbableState climbable = resolveClimbable(current, surface, input, frame, intent);
        return new BedrockClimbState(surface, scaffolding, climbable);
    }

    private static BedrockScaffoldingState resolveScaffolding(
        BedrockClimbSurface surface,
        ClimbInput input,
        ScaffoldingVerticalBranch scaffoldingVerticalBranch
    ) {
        // The vanilla scaffolding action owns the descend flag and the -0.15
        // vertical velocity write, before the jump systems run.
        if (!surface.descendAllowed() || !input.descend()) {
            return BedrockScaffoldingState.NONE;
        }
        return switch (scaffoldingVerticalBranch) {
            case DESCEND -> BedrockScaffoldingState.DESCENDING;
            case SOURCE -> BedrockScaffoldingState.NONE;
        };
    }

    private static BedrockClimbableState resolveClimbable(
        BedrockMovementState current,
        BedrockClimbSurface surface,
        ClimbInput input,
        BedrockInputFrame frame,
        BedrockInputIntent intent
    ) {
        if (!surface.climbing() || surface.inScaffolding()) {
            return BedrockClimbableState.NONE;
        }
        return new BedrockClimbableState(
            input.upward() && !intent.jump().start(),
            frame.sneaking() && !input.upward(),
            current.collisionFlags().horizontalBlockContact()
        );
    }

    record ClimbInput(boolean descend, boolean upward) {
        static ClimbInput from(BedrockInputFrame frame, BedrockInputIntent intent) {
            return new ClimbInput(
                BedrockLiquidVerticalMovement.descendInput(intent),
                intent.vertical().upwardClimbInput(frame.jumping())
            );
        }
    }
}
