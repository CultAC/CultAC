package ac.cult.cultac.bedrock.prediction.simulation.reconciliation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockPositionTranslator;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockCollisionProjectionResolver;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockCollisionSweep;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockAerialMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockBlockSurfaceMovement;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class BedrockAcceptedDiffVelocity {
    private BedrockAcceptedDiffVelocity() {
    }

    static List<AcceptedState> apply(
        BedrockMovementResult movementResult,
        BedrockMovementState state,
        Vec3d acceptedDiff,
        boolean selectedXCollision,
        boolean selectedZCollision,
        boolean canStep
    ) {
        BedrockAcceptedEndpointEvidence evidence = BedrockAcceptedEndpointEvidence.from(
            movementResult, state, acceptedDiff, canStep
        );
        List<BedrockMovementState> contactStates = withAcceptedEndpointClimbableContacts(
            movementResult, state, evidence);
        if (contactStates.size() == 1) {
            return applyWithContact(
                movementResult, contactStates.getFirst(), evidence,
                acceptedDiff, selectedXCollision, selectedZCollision);
        }
        Set<AcceptedState> acceptedStates = new LinkedHashSet<>();
        for (BedrockMovementState contactState : contactStates) {
            acceptedStates.addAll(applyWithContact(
                movementResult, contactState, evidence,
                acceptedDiff, selectedXCollision, selectedZCollision));
        }
        return List.copyOf(acceptedStates);
    }

    private static List<AcceptedState> applyWithContact(
        BedrockMovementResult movementResult,
        BedrockMovementState state,
        BedrockAcceptedEndpointEvidence evidence,
        Vec3d acceptedDiff,
        boolean selectedXCollision,
        boolean selectedZCollision
    ) {
        if (movementResult.selectedGlidingTravel() && state.gliding()) {
            Vec3d glidingVelocity = acceptedCollisionVelocity(state, acceptedDiff);
            glidingVelocity = BedrockBlockSurfaceMovement.applyInsideBlockAfterPostMoveEffects(
                glidingVelocity,
                movementResult.honeySlideState(),
                state.physicalFeetPosition(),
                evidence.committedDimensions());
            if (glidingVelocity.equals(state.velocity())) {
                return List.of(new AcceptedState(state, evidence));
            }
            return List.of(new AcceptedState(
                state.withVelocityAndCollisionFlags(glidingVelocity, state.collisionFlags()), evidence));
        }
        if (movementResult.blockMovementSlowdownClearsVelocity()) {
            return List.of(new AcceptedState(state, evidence));
        }
        List<BedrockAcceptedDiffHorizontalVelocity.AxisVelocity> horizontalVelocities =
            BedrockAcceptedDiffHorizontalVelocity.resolve(
                evidence, selectedXCollision, selectedZCollision);
        double velocityY = BedrockAcceptedDiffVerticalVelocity.resolve(evidence);
        BedrockCollisionFlags flags = acceptedEndpointLiquidClearsGround(evidence)
            ? state.collisionFlags().withOnGround(false)
            : acceptedDiffPostMoveBounceGrounds(evidence)
            ? state.collisionFlags().withOnGround(true).withVerticalCollision(true)
            : evidence.projectedStep()
            ? projectedStepVerticalCollisionFlags(movementResult, state.collisionFlags())
            : acceptedDiffDownwardSupportGrounds(movementResult, state, acceptedDiff)
            ? groundedVerticalCollision(state.collisionFlags())
            : acceptedDiffHasNonDownwardVerticalMove(movementResult, acceptedDiff)
            ? acceptedDiffVerticalCollisionFlags(movementResult, state.collisionFlags(), acceptedDiff)
            : state.collisionFlags();
        boolean waterTravelActive = evidence.waterTravelActive();
        Medium movementBranch = evidence.movementBranch(flags, waterTravelActive);
        Set<BedrockMovementState> states = new LinkedHashSet<>();
        for (BedrockAcceptedDiffHorizontalVelocity.AxisVelocity horizontalVelocity : horizontalVelocities) {
            Vec3d velocity = applyAcceptedEndpointStandingSurfaceHorizontalSlowdown(
                movementResult,
                state,
                flags,
                horizontalVelocity.withY(velocityY));
            velocity = applyAcceptedEndpointEntityInside(evidence, velocity);
            if (velocity.equals(state.velocity())
                && flags.equals(state.collisionFlags())
                && state.waterTravelFlag() == waterTravelActive
                && state.movementBranch() == movementBranch) {
                states.add(state);
                continue;
            }
            states.add(state.withVelocityAndCollisionFlags(velocity, flags)
                .withWaterTravelFlag(waterTravelActive)
                .withMovementBranch(movementBranch));
        }
        return states.stream().map(next -> new AcceptedState(next, evidence)).toList();
    }

    private static List<BedrockMovementState> withAcceptedEndpointClimbableContacts(
        BedrockMovementResult movementResult,
        BedrockMovementState state,
        BedrockAcceptedEndpointEvidence evidence
    ) {
        BedrockClimbableContact contact = BedrockClimbableContact.fromBlockWorld(
            movementResult.movementContext().worldState().blockCollisionWorld(),
            state.physicalFeetPosition(),
            evidence.committedDimensions().width(),
            evidence.committedDimensions().height(),
            movementResult.movementContext().equipmentState().leatherBoots()
        );
        BedrockMovementState endpoint = state.withClimbableContact(contact);
        BedrockClimbableContact carried = movementResult.previousState().climbableContact();
        BedrockClimbableContact currentPositionContact = BedrockClimbableContact.fromBlockWorld(
            movementResult.movementContext().worldState().blockCollisionWorld(),
            movementResult.previousState().physicalFeetPosition(),
            movementResult.previousState().playerDimensions().width(),
            movementResult.previousState().playerDimensions().height(),
            movementResult.movementContext().equipmentState().leatherBoots()
        );
        if (!carried.equals(currentPositionContact)) {

            return List.of(endpoint);
        }
        if (contact.equals(carried)) {
            return List.of(endpoint);
        }

        return List.of(endpoint, state.withClimbableContact(carried));
    }

    static double airDraggedVelocityWithGravity(double verticalVelocity) {
        return BedrockAerialMovement.airDraggedVelocity(verticalVelocity, BedrockAerialMovement.AIR_GRAVITY);
    }

    private static Vec3d applyAcceptedEndpointStandingSurfaceHorizontalSlowdown(
        BedrockMovementResult movementResult,
        BedrockMovementState state,
        BedrockCollisionFlags flags,
        Vec3d velocity
    ) {

        return BedrockBlockSurfaceMovement.applyStandingAfterMove(
            velocity,
            state.physicalFeetPosition(),
            state.inputFrame().sneaking(),
            flags.onGround(),
            movementResult.movementContext().worldState().blockCollisionWorld(),
            movementResult.movementContext().playerDimensionsState());
    }

    private static boolean acceptedEndpointLiquidClearsGround(BedrockAcceptedEndpointEvidence evidence) {
        BedrockMovementResult movementResult = evidence.movementResult();
        if (movementResult.movementContext().inWater() || movementResult.movementContext().inLava()) {
            return false;
        }
        BedrockMovementContext endpointContext = evidence.fluidContext();
        return endpointContext.inWater()
            || endpointContext.inLava()
            || endpointContext.liquidMovementMedium() == Medium.LAVA;
    }

    private static Vec3d applyAcceptedEndpointEntityInside(
        BedrockAcceptedEndpointEvidence evidence,
        Vec3d velocity
    ) {
        BedrockMovementResult movementResult = evidence.movementResult();
        BedrockMovementState state = evidence.state();
        return BedrockBlockSurfaceMovement.applyInsideBlockAfterPostMoveEffects(
            velocity,
            movementResult.honeySlideState(),
            state.physicalFeetPosition(),
            evidence.committedDimensions());
    }

    private static boolean acceptedDiffPostMoveBounceGrounds(BedrockAcceptedEndpointEvidence evidence) {
        BedrockMovementResult movementResult = evidence.movementResult();
        if (movementResult.predictedState().autoClimbTravel()) {

            return false;
        }

        return BedrockAcceptedDiffVerticalVelocity.acceptedPostMoveBounceVelocity(evidence).isPresent();
    }

    private static boolean acceptedDiffDownwardSupportGrounds(
        BedrockMovementResult movementResult,
        BedrockMovementState state,
        Vec3d acceptedDiff
    ) {
        if (state.autoClimbTravel() || movementResult.predictedState().autoClimbTravel()) {

            return false;
        }
        Vec3d previous = movementResult.previousState().physicalFeetPosition();
        Vec3d rawMove = movementResult.rawPredictedPhysicalFeetPosition().subtract(previous);
        if (rawMove.y() >= 0.0D) {
            return false;
        }
        Vec3d packetSpaceRequestedDelta = new Vec3d(acceptedDiff.x(), rawMove.y(), acceptedDiff.z());
        return BedrockCollisionProjectionResolver.supportPosition(movementResult, packetSpaceRequestedDelta)

            .map(position -> Math.abs(position.y() - state.physicalFeetPosition().y()) <= BedrockCollisionSweep.EPSILON)
            .orElse(false);
    }

    private static boolean acceptedDiffHasNonDownwardVerticalMove(
        BedrockMovementResult movementResult,
        Vec3d acceptedDiff
    ) {
        double rawMoveY = packetVisibleRawMove(movementResult).y();
        return rawMoveY >= 0.0D
            && (Math.abs(rawMoveY) > BedrockCollisionSweep.EPSILON
            || Math.abs(acceptedDiff.y()) > BedrockCollisionSweep.EPSILON);
    }

    private static BedrockCollisionFlags acceptedDiffVerticalCollisionFlags(
        BedrockMovementResult movementResult,
        BedrockCollisionFlags flags,
        Vec3d acceptedDiff
    ) {
        double rawMoveY = packetVisibleRawMove(movementResult).y();
        boolean verticalCollision = Math.abs(rawMoveY - acceptedDiff.y()) > BedrockCollisionSweep.EPSILON;
        boolean verticalCollisionBelow = verticalCollision && rawMoveY < 0.0D;

        return new BedrockCollisionFlags(
            verticalCollisionBelow,
            flags.horizontalCollision(),
            verticalCollision,
            flags.horizontalBlockContact(),
            flags.liquidClimbOut(),
            verticalCollisionBelow,
            flags.xCollision(),
            flags.zCollision()
        );
    }

    private static Vec3d packetVisibleRawMove(BedrockMovementResult movementResult) {
        Vec3d previous = movementResult.previousState().physicalFeetPosition();
        Vec3d rawPacketEndpoint = BedrockPositionTranslator.normalizePhysicalFeetPosition(
            movementResult.rawPredictedPhysicalFeetPosition(), movementResult.previousState().coordinateFrame());
        return rawPacketEndpoint.subtract(previous);
    }

    private static BedrockCollisionFlags groundedVerticalCollision(BedrockCollisionFlags flags) {
        return new BedrockCollisionFlags(
            true,
            flags.horizontalCollision(),
            true,
            flags.horizontalBlockContact(),
            flags.liquidClimbOut(),
            true,
            flags.xCollision(),
            flags.zCollision()
        );
    }

    private static BedrockCollisionFlags projectedStepVerticalCollisionFlags(
        BedrockMovementResult movementResult,
        BedrockCollisionFlags flags
    ) {
        double rawMoveY = movementResult.rawPredictedPhysicalFeetPosition().y()
            - movementResult.previousState().physicalFeetPosition().y();

        boolean onGround = rawMoveY < 0.0D;
        return new BedrockCollisionFlags(
            onGround,
            flags.horizontalCollision(),
            true,
            flags.horizontalBlockContact(),
            flags.liquidClimbOut(),
            onGround,
            flags.xCollision(),
            flags.zCollision()
        );
    }

    private static Vec3d acceptedCollisionVelocity(BedrockMovementState state, Vec3d acceptedDiff) {
        double velocityX = state.collisionFlags().xCollision() ? 0.0D : acceptedDiff.x();
        double velocityY = state.collisionFlags().verticalCollision() ? 0.0D : acceptedDiff.y();
        double velocityZ = state.collisionFlags().zCollision() ? 0.0D : acceptedDiff.z();
        return new Vec3d(velocityX, velocityY, velocityZ);
    }

    record AcceptedState(BedrockMovementState state, BedrockAcceptedEndpointEvidence evidence) {
        AcceptedState {
            java.util.Objects.requireNonNull(state, "state");
            java.util.Objects.requireNonNull(evidence, "evidence");
        }
    }
}
