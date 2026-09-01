package ac.grim.grimac.bedrock.prediction.simulation.frame;

import java.util.Objects;

public record BedrockTravelOptions(
    StepMode stepMode,
    double maxUpStep,
    SprintTravelSpeedMode sprintTravelSpeedMode,
    SprintJumpImpulseMode sprintJumpImpulseMode
) {
    public BedrockTravelOptions {
        stepMode = Objects.requireNonNull(stepMode, "stepMode");
        sprintTravelSpeedMode = Objects.requireNonNull(sprintTravelSpeedMode, "sprintTravelSpeedMode");
        sprintJumpImpulseMode = Objects.requireNonNull(sprintJumpImpulseMode, "sprintJumpImpulseMode");
        if (!Double.isFinite(maxUpStep) || maxUpStep < 0.0D) {
            maxUpStep = 0.0D;
        }
    }

    public static BedrockTravelOptions vanilla(boolean canStep, double maxUpStep) {
        return vanilla(StepMode.fromCanStep(canStep), maxUpStep);
    }

    public static BedrockTravelOptions vanilla(StepMode stepMode, double maxUpStep) {
        return new BedrockTravelOptions(
            stepMode,
            maxUpStep,
            SprintTravelSpeedMode.ORDERED_ACTOR_FLAG,
            SprintJumpImpulseMode.ORDERED_ACTOR_FLAG
        );
    }

    public BedrockTravelOptions withSprintTravelSpeedMode(SprintTravelSpeedMode sprintTravelSpeedMode) {
        return new BedrockTravelOptions(
            stepMode,
            maxUpStep,
            sprintTravelSpeedMode,
            sprintJumpImpulseMode
        );
    }

    public BedrockTravelOptions withSprintJumpImpulseMode(SprintJumpImpulseMode sprintJumpImpulseMode) {
        return new BedrockTravelOptions(
            stepMode,
            maxUpStep,
            sprintTravelSpeedMode,
            sprintJumpImpulseMode
        );
    }

    public boolean canStep() {
        return stepMode.canStep();
    }

    public enum StepMode {
        NO_AUTO_STEP(false),
        AUTO_STEP(true);

        private final boolean canStep;

        StepMode(boolean canStep) {
            this.canStep = canStep;
        }

        boolean canStep() {
            return canStep;
        }

        static StepMode fromCanStep(boolean canStep) {
            return canStep ? AUTO_STEP : NO_AUTO_STEP;
        }
    }

    public enum SprintTravelSpeedMode {
        ORDERED_ACTOR_FLAG,
        FORCE_SPRINTING,
        FORCE_NOT_SPRINTING;

        boolean actorSprinting(boolean orderedActorSprinting) {
            return switch (this) {
                case ORDERED_ACTOR_FLAG -> orderedActorSprinting;
                case FORCE_SPRINTING -> true;
                case FORCE_NOT_SPRINTING -> false;
            };
        }

        boolean sprintSpeedInput(boolean orderedActorSprinting) {
            return actorSprinting(orderedActorSprinting);
        }
    }

    public enum SprintJumpImpulseMode {
        ORDERED_ACTOR_FLAG,
        FORCE_SPRINTING,
        FORCE_NOT_SPRINTING;

        boolean actorSprinting(boolean orderedActorSprinting) {
            return switch (this) {
                case ORDERED_ACTOR_FLAG -> orderedActorSprinting;
                case FORCE_SPRINTING -> true;
                case FORCE_NOT_SPRINTING -> false;
            };
        }
    }

}
