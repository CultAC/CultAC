package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState.TickStart;
import java.util.Objects;

public record BedrockTravelOptions(
        StepMode stepMode,
        double maxUpStep,
        SprintTravelSpeedMode sprintTravelSpeedMode,
        SprintJumpImpulseMode sprintJumpImpulseMode,
        TickStart tickStart,
        int solidActorOrder) {
    public BedrockTravelOptions(
            StepMode stepMode,
            double maxUpStep,
            SprintTravelSpeedMode sprintTravelSpeedMode,
            SprintJumpImpulseMode sprintJumpImpulseMode,
            TickStart tickStart) {
        this(stepMode, maxUpStep, sprintTravelSpeedMode, sprintJumpImpulseMode, tickStart, 0);
    }

    public BedrockTravelOptions(
            StepMode stepMode,
            double maxUpStep,
            SprintTravelSpeedMode sprintTravelSpeedMode,
            SprintJumpImpulseMode sprintJumpImpulseMode) {
        this(stepMode, maxUpStep, sprintTravelSpeedMode, sprintJumpImpulseMode, TickStart.MOVE);
    }

    public BedrockTravelOptions withTickStart(TickStart start) {
        return new BedrockTravelOptions(
                stepMode, maxUpStep, sprintTravelSpeedMode, sprintJumpImpulseMode, start, solidActorOrder);
    }

    /** The order the move meets its solid actors in (see {@code BedrockSolidActorOrders}); 0 is the collected order. */
    public BedrockTravelOptions withSolidActorOrder(int order) {
        return new BedrockTravelOptions(
                stepMode, maxUpStep, sprintTravelSpeedMode, sprintJumpImpulseMode, tickStart, order);
    }

    /** A teleported actor's tick runs no travel. */
    public boolean travelActive() {
        return tickStart != TickStart.TELEPORT;
    }

    /** The actor's lerp step drops the motion the tick carries in. */
    public boolean lerpStep() {
        return tickStart == TickStart.LERP_STEP;
    }

    public BedrockTravelOptions {
        stepMode = Objects.requireNonNull(stepMode, "stepMode");
        tickStart = Objects.requireNonNull(tickStart, "tickStart");
        sprintTravelSpeedMode = Objects.requireNonNull(sprintTravelSpeedMode, "sprintTravelSpeedMode");
        sprintJumpImpulseMode = Objects.requireNonNull(sprintJumpImpulseMode, "sprintJumpImpulseMode");
        if (!Double.isFinite(maxUpStep) || maxUpStep < 0.0D) {
            maxUpStep = 0.0D;
        }
        if (solidActorOrder < 0) throw new IllegalArgumentException("solidActorOrder");
    }

    public static BedrockTravelOptions vanilla(boolean canStep, double maxUpStep) {
        return vanilla(StepMode.fromCanStep(canStep), maxUpStep);
    }

    public static BedrockTravelOptions vanilla(StepMode stepMode, double maxUpStep) {
        return new BedrockTravelOptions(
                stepMode,
                maxUpStep,
                SprintTravelSpeedMode.ORDERED_ACTOR_FLAG,
                SprintJumpImpulseMode.ORDERED_ACTOR_FLAG);
    }

    public BedrockTravelOptions withSprintTravelSpeedMode(SprintTravelSpeedMode sprintTravelSpeedMode) {
        return new BedrockTravelOptions(
                stepMode, maxUpStep, sprintTravelSpeedMode, sprintJumpImpulseMode, tickStart, solidActorOrder);
    }

    public BedrockTravelOptions withSprintJumpImpulseMode(SprintJumpImpulseMode sprintJumpImpulseMode) {
        return new BedrockTravelOptions(
                stepMode, maxUpStep, sprintTravelSpeedMode, sprintJumpImpulseMode, tickStart, solidActorOrder);
    }

    public boolean canStep() {
        return travelActive() && stepMode.canStep();
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
