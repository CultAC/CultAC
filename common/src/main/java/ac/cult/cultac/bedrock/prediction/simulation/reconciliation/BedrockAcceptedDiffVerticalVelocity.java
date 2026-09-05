package ac.cult.cultac.bedrock.prediction.simulation.reconciliation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockStandingBlockResolver;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockLiquidVerticalMovement;
import ac.cult.cultac.bedrock.prediction.simulation.postmove.BedrockBounceBlockMovement;
import ac.cult.cultac.bedrock.prediction.simulation.postmove.BedrockEntityInsideMovement;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.BounceBlockState;
import java.util.OptionalDouble;

final class BedrockAcceptedDiffVerticalVelocity {
    private BedrockAcceptedDiffVerticalVelocity() {
    }

    static double resolve(BedrockAcceptedEndpointEvidence evidence) {
        BedrockMovementResult movementResult = evidence.movementResult();
        BedrockMovementState state = evidence.state();
        Vec3d acceptedDiff = evidence.acceptedDiff();
        OptionalDouble acceptedPostMoveBounce = acceptedPostMoveBounceVelocity(evidence);
        if (acceptedPostMoveBounce.isPresent()) {

            return acceptedPostMoveBounce.getAsDouble();
        }
        if (evidence.projectedStep()) {

            if (movementResult.selectedGlidingTravel()) {
                return 0.0D;
            }
            return BedrockAcceptedDiffVelocity.airDraggedVelocityWithGravity(0.0D);
        }
        OptionalDouble acceptedBubbleColumnVelocity = acceptedPostMoveBubbleColumnVelocity(evidence);
        if (acceptedBubbleColumnVelocity.isPresent()) {
            return acceptedBubbleColumnVelocity.getAsDouble();
        }
        if (movementResult.blockMovementSlowdownClearsVelocity()) {
            return movementResult.predictedState().velocity().y();
        }
        OptionalDouble collisionVelocity = verticalCollisionDerivedVelocity(evidence);
        if (collisionVelocity.isPresent()) {
            return collisionVelocity.getAsDouble();
        }
        OptionalDouble liquidVelocity = liquidTravelDerivedVelocity(movementResult, state, acceptedDiff);
        if (liquidVelocity.isPresent()) {
            return liquidVelocity.getAsDouble();
        }
        return airTravelDerivesVelocity(movementResult)
            ? BedrockAcceptedDiffVelocity.airDraggedVelocityWithGravity(acceptedDiff.y())
            : state.velocity().y();
    }

    private static OptionalDouble verticalCollisionDerivedVelocity(BedrockAcceptedEndpointEvidence evidence) {
        BedrockMovementResult movementResult = evidence.movementResult();
        BedrockMovementState state = evidence.state();
        var projection = evidence.collisionProjection();

        boolean verticalCollision = projection == null
            ? state.collisionFlags().verticalCollision()
            : projection.move().collisionFlags().verticalCollision();
        if (!verticalCollision || state.collisionFlags().liquidClimbOut()) {
            return OptionalDouble.empty();
        }
        if (liquidTravelEffects(movementResult)) {
            return OptionalDouble.of(state.velocity().y());
        }
        if (upwardVelocityWithoutGroundContact(state)) {
            return OptionalDouble.of(state.velocity().y());
        }
        if (movementResult.selectedGlidingTravel()) {
            return OptionalDouble.of(0.0D);
        }
        return OptionalDouble.of(BedrockAcceptedDiffVelocity.airDraggedVelocityWithGravity(0.0D));
    }

    static OptionalDouble acceptedPostMoveBounceVelocity(BedrockAcceptedEndpointEvidence evidence) {
        BedrockMovementResult movementResult = evidence.movementResult();
        BedrockMovementState state = evidence.state();
        Vec3d acceptedDiff = evidence.acceptedDiff();
        BlockCollisionWorld blockWorld = movementResult.movementContext().worldState().blockCollisionWorld();
        if (blockWorld.isEmpty()) {
            return OptionalDouble.empty();
        }
        var projection = evidence.collisionProjection();
        if (projection == null
            || !projection.move().collisionFlags().onGround()
            || projection.requestedDelta().y() >= 0.0D) {
            return OptionalDouble.empty();
        }

        var acceptedSupport = BedrockStandingBlockResolver.resolve(
            state.physicalFeetPosition(), blockWorld, evidence.committedDimensions()
        );
        var standingBlock = acceptedSupport.isPresent()
            ? BounceBlockState.fromCollisionBlock(
                acceptedSupport.get().block(), acceptedSupport.get().surfaceY()
            )
            : projection.collisionMove().selectedMove().yCollisionBlock()
                .flatMap(block -> BounceBlockState.fromCollisionBlock(
                    block, state.physicalFeetPosition().y()
                ));
        if (standingBlock.isEmpty()) {
            return OptionalDouble.empty();
        }
        Vec3d currentFeetPosition = state.physicalFeetPosition().subtract(acceptedDiff);
        BedrockBounceBlockMovement.Result bounce = BedrockBounceBlockMovement.applyAfterVerticalReset(
            currentFeetPosition,
            projection.move().velocity(),
            projection.requestedDelta().y(),
            movementResult.predictedState().inputFrame(),
            standingBlock.get(),
            !movementResult.selectedGlidingTravel()
        );
        return bounce.bounced()
            ? OptionalDouble.of(bounce.velocity().y())
            : OptionalDouble.empty();
    }

    private static OptionalDouble acceptedPostMoveBubbleColumnVelocity(BedrockAcceptedEndpointEvidence evidence) {
        BedrockMovementResult movementResult = evidence.movementResult();
        BedrockMovementState state = evidence.state();
        Vec3d acceptedDiff = evidence.acceptedDiff();
        BedrockMovementContext acceptedEndpointContext = evidence.fluidContext();
        boolean explicitBubbleLayers = !acceptedEndpointContext.worldState().fluidState()
            .bubbleColumnState().layers().isEmpty();
        if (!explicitBubbleLayers
            && !acceptedEndpointContext.inUpwardBubbleColumn()
            && !acceptedEndpointContext.inDownwardBubbleColumn()) {
            return OptionalDouble.empty();
        }
        double baseVelocityY = acceptedDiffBaseVelocityY(movementResult, state, acceptedDiff);
        Vec3d velocity = BedrockEntityInsideMovement.applyBubbleColumns(
            acceptedEndpointContext,
            state.gliding(),
            new Vec3d(state.velocity().x(), baseVelocityY, state.velocity().z()),
            state.physicalFeetPosition(),
            state.simulationTick());
        return OptionalDouble.of(velocity.y());
    }

    private static double acceptedDiffBaseVelocityY(
        BedrockMovementResult movementResult,
        BedrockMovementState state,
        Vec3d acceptedDiff
    ) {
        double liquidMoveY = state.collisionFlags().verticalCollision() ? 0.0D : acceptedDiff.y();
        if (waterTravelEffects(movementResult)) {
            return BedrockLiquidVerticalMovement.waterNextTickVelocityY(
                liquidMoveY,
                waterGravityApplies(movementResult, state));
        }
        if (lavaTravelEffects(movementResult)) {
            return BedrockLiquidVerticalMovement.lavaNextTickVelocityY(
                liquidMoveY,
                lavaGravityApplies(movementResult));
        }
        if (airTravelDerivesVelocity(movementResult)) {
            return BedrockAcceptedDiffVelocity.airDraggedVelocityWithGravity(acceptedDiff.y());
        }
        return state.velocity().y();
    }

    private static boolean upwardVelocityWithoutGroundContact(BedrockMovementState state) {
        return !state.collisionFlags().onGround() && state.velocity().y() > 0.0D;
    }

    private static OptionalDouble liquidTravelDerivedVelocity(
        BedrockMovementResult movementResult,
        BedrockMovementState state,
        Vec3d acceptedDiff
    ) {
        if (waterTravelEffects(movementResult)) {
            return OptionalDouble.of(BedrockLiquidVerticalMovement.waterNextTickVelocityY(
                acceptedDiff.y(),
                waterGravityApplies(movementResult, state)));
        }
        if (lavaTravelEffects(movementResult)) {
            return OptionalDouble.of(BedrockLiquidVerticalMovement.lavaNextTickVelocityY(
                acceptedDiff.y(),
                lavaGravityApplies(movementResult)));
        }
        return OptionalDouble.empty();
    }

    private static boolean airTravelDerivesVelocity(BedrockMovementResult movementResult) {
        return !movementResult.movementContext().inWater()
            && !movementResult.movementContext().inLava();
    }

    private static boolean liquidTravelEffects(BedrockMovementResult movementResult) {
        return waterTravelEffects(movementResult) || lavaTravelEffects(movementResult);
    }

    private static boolean waterTravelEffects(BedrockMovementResult movementResult) {
        return movementResult.movementContext().inWater()
            && movementResult.movementContext().liquidMovementMedium() != Medium.LAVA;
    }

    private static boolean lavaTravelEffects(BedrockMovementResult movementResult) {
        return movementResult.movementContext().inLava()
            || movementResult.movementContext().liquidMovementMedium() == Medium.LAVA;
    }

    private static boolean waterGravityApplies(BedrockMovementResult movementResult, BedrockMovementState state) {
        return waterGravityApplies(movementResult.movementContext(), state);
    }

    private static boolean waterGravityApplies(BedrockMovementContext context, BedrockMovementState state) {
        return !state.swimming() && !context.movementAbilityFlying();
    }

    private static boolean lavaGravityApplies(BedrockMovementResult movementResult) {
        return lavaGravityApplies(movementResult.movementContext());
    }

    private static boolean lavaGravityApplies(BedrockMovementContext context) {
        return !context.movementAbilityFlying();
    }
}
