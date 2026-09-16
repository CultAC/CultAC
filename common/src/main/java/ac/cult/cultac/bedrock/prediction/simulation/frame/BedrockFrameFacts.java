package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.BedrockBoundingBoxMode;
import ac.cult.cultac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.HoneySlideState;
import ac.cult.cultac.bedrock.prediction.world.PowderSnowContactState;
import ac.cult.cultac.bedrock.prediction.world.StandingSurfaceState;
import java.util.Objects;
import java.util.Set;

public record BedrockFrameFacts(
    BedrockMovementContext context,
    BedrockBoundingBoxMode boundingBoxMode,
    PlayerDimensionsState movementDimensions,
    BedrockSwimmingMovement.SwimmingState swimming,
    BedrockClimbState climb,
    boolean inWaterFlag,
    boolean lavaTravelFlag,
    boolean inPowderSnow,
    boolean rawPowderSnowAtFeetAscendable,
    long powderSnowTicks,
    BlockMovementSlowdownState blockMovementSlowdownState,
    HoneySlideState honeySlideState,
    StandingSurfaceState standingSurfaceState
) {
    public BedrockFrameFacts {
        context = Objects.requireNonNull(context, "context");
        boundingBoxMode = Objects.requireNonNull(boundingBoxMode, "boundingBoxMode");
        movementDimensions = Objects.requireNonNull(movementDimensions, "movementDimensions");
        swimming = Objects.requireNonNull(swimming, "swimming");
        climb = Objects.requireNonNull(climb, "climb");
        blockMovementSlowdownState = Objects.requireNonNull(blockMovementSlowdownState, "blockMovementSlowdownState");
        honeySlideState = Objects.requireNonNull(honeySlideState, "honeySlideState");
        standingSurfaceState = Objects.requireNonNull(standingSurfaceState, "standingSurfaceState");
    }

    public static BedrockFrameFacts from(BedrockTravelInput input, boolean spinActive) {
        BedrockMovementState current = input.previousState();
        BedrockInputFrame frame = input.inputFrame();
        BedrockMovementContext context = input.worldSnapshot().movementContext();
        PowderSnowContactState powderSnowContact = input.worldSnapshot().initialPowderSnowContact();
        boolean inPowderSnow = powderSnowContact.actorIntersection();
        boolean powderSnowSurfaceSink = powderSnowContact.feetSurface();
        boolean travelOnGround = current.movementGrounded();
        BedrockActorDimensions.Resolved resolvedDimensions = BedrockActorDimensions.resolve(
            current,
            context.playerDimensionsState(),
            frame,
            spinActive
        );
        PlayerDimensionsState movementDimensions = resolvedDimensions.dimensions();
        // Water sensing precedes input pose changes; spin actions have already applied.
        PlayerDimensionsState sensingDimensions = spinActive ? movementDimensions : current.playerDimensions();
        boolean inWaterFlag = BedrockLiquidSensing.inWaterFlag(
            context,
            current.physicalFeetPosition(),
            sensingDimensions
        );
        boolean lavaTravelFlag = BedrockLiquidSensing.lavaTravelFlag(
            context,
            current.physicalFeetPosition(),
            sensingDimensions
        );
        return new BedrockFrameFacts(
            context,
            resolvedDimensions.mode(),
            movementDimensions,
            BedrockSwimmingMovement.initial(current, context),
            BedrockClimbMovement.resolveSurface(
                current.climbableContact(),
                context.inWater(),
                context.inLava()
            ),
            inWaterFlag,
            lavaTravelFlag,
            inPowderSnow,
            powderSnowContact.rawAtFeetAscendable(),
            powderSnowSurfaceSink || inPowderSnow ? current.powderSnowTicks() + 1L : 0L,
            input.worldSnapshot().initialBlockMovementSlowdownState(),
            input.worldSnapshot().honeySlideState(),
            travelOnGround
                ? BedrockStandingSurfaceResolver.travelSurfaceFromBlockWorld(
                    current.physicalFeetPosition(),
                    input.worldSnapshot().blockCollisionWorld(),
                    movementDimensions)
                : new StandingSurfaceState(Set.of())
        );
    }

    public BedrockFrameFacts withContext(BedrockMovementContext value) {
        return new BedrockFrameFacts(value, boundingBoxMode, movementDimensions, swimming, climb,
            inWaterFlag, lavaTravelFlag, inPowderSnow, rawPowderSnowAtFeetAscendable, powderSnowTicks,
            blockMovementSlowdownState, honeySlideState, standingSurfaceState);
    }

    public BedrockFrameFacts withClimb(BedrockClimbState climb) {
        return new BedrockFrameFacts(
            context,
            boundingBoxMode,
            movementDimensions,
            swimming,
            climb,
            inWaterFlag,
            lavaTravelFlag,
            inPowderSnow,
            rawPowderSnowAtFeetAscendable,
            powderSnowTicks,
            blockMovementSlowdownState,
            honeySlideState,
            standingSurfaceState
        );
    }

    public BedrockFrameFacts withSwimming(BedrockSwimmingMovement.SwimmingState swimming) {
        return new BedrockFrameFacts(
            context,
            boundingBoxMode,
            movementDimensions,
            swimming,
            climb,
            inWaterFlag,
            lavaTravelFlag,
            inPowderSnow,
            rawPowderSnowAtFeetAscendable,
            powderSnowTicks,
            blockMovementSlowdownState,
            honeySlideState,
            standingSurfaceState
        );
    }

    public BedrockEffectState effectState() {
        return context.effectState();
    }

    public BlockCollisionWorld blockCollisionWorld() {
        return context.worldState().blockCollisionWorld();
    }

    public boolean inWater() {
        return inWaterFlag;
    }

    public boolean inLava() {
        return context.inLava();
    }

    public boolean navigationCanWalkInLava() {
        return context.inLava() && context.navigationCanWalkInLava();
    }
}
