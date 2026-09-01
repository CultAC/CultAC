package ac.grim.grimac.bedrock.prediction.simulation.reconciliation;

import ac.grim.grimac.bedrock.prediction.api.BedrockMovementResult;
import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.grim.grimac.bedrock.prediction.model.Medium;
import ac.grim.grimac.bedrock.prediction.simulation.postmove.BedrockEntityInsideMovement;
import ac.grim.grimac.bedrock.prediction.simulation.postmove.BedrockLiquidClimbOutMovement;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import java.util.ArrayList;
import java.util.List;

final class BedrockEndTickVelocityBranches {
    private static final int MAX_SWIM_HOP_VELOCITIES = 2;
    private static final int MAX_CLIMBABLE_VELOCITIES = 3;

    private BedrockEndTickVelocityBranches() {
    }

    static List<BedrockNextTickStateDeriver.DerivedState> fromAcceptedDiff(
            BedrockAcceptedEndpointEvidence evidence,
            BedrockMovementState acceptedDiffState,
            BedrockMovementState source
    ) {
        BedrockMovementResult movementResult = evidence.movementResult();
        ArrayList<BedrockNextTickStateDeriver.DerivedState> states = new ArrayList<>();
        int swimHopStart = states.size();
        addLiquidClimbOutVelocity(states, evidence, acceptedDiffState, source);
        requireBoundedSource(states, swimHopStart, MAX_SWIM_HOP_VELOCITIES, "swim-hop");
        BedrockMovementState baseState = withoutSwimHopEvent(acceptedDiffState);
        int climbableStart = states.size();
        states.add(new BedrockNextTickStateDeriver.DerivedState(baseState, source));
        addClimbableVelocities(states, movementResult, baseState, source);
        requireBoundedSource(states, climbableStart, MAX_CLIMBABLE_VELOCITIES, "climbable");
        return List.copyOf(states);
    }

    private static void addLiquidClimbOutVelocity(
            List<BedrockNextTickStateDeriver.DerivedState> states,
            BedrockAcceptedEndpointEvidence evidence,
            BedrockMovementState state,
            BedrockMovementState source
    ) {
        BedrockMovementResult movementResult = evidence.movementResult();
        BedrockMovementState climbOutState = liquidClimbOutState(movementResult, state);
        if (climbOutState == null) {
            return;
        }
        addVelocityY(
                states,
                withSwimHopEvent(climbOutState),
                liquidClimbOutVelocityYAfterEntityInside(evidence, climbOutState),
                source);
    }

    private static double liquidClimbOutVelocityYAfterEntityInside(
        BedrockAcceptedEndpointEvidence evidence,
        BedrockMovementState state
    ) {
        Vec3d climbOutVelocity = new Vec3d(
            state.velocity().x(),
            BedrockLiquidClimbOutMovement.CLIMB_OUT_VELOCITY_Y,
            state.velocity().z());
        return BedrockEntityInsideMovement.applyBubbleColumns(
            evidence.fluidContext(),
            state.gliding(),
            climbOutVelocity,
            state.physicalFeetPosition(),
            state.simulationTick()).y();
    }

    private static BedrockMovementState liquidClimbOutState(
            BedrockMovementResult movementResult,
            BedrockMovementState state
    ) {
        if (state.collisionFlags().liquidClimbOut()) {
            return state;
        }
        if (!liquidTravelActive(movementResult)) {
            return null;
        }
        BedrockCollisionFlags climbOutFlags = liquidClimbOutFlags(movementResult, state);
        if (!climbOutFlags.horizontalCollision() && !climbOutFlags.horizontalBlockContact()) {
            return null;
        }
        Vec3d velocity = zeroBlockedHorizontalAxes(state.velocity(), climbOutFlags);
        if (!BedrockLiquidClimbOutMovement.applies(
            true,
            movementResult.previousState().physicalFeetPosition(),
            state.physicalFeetPosition(),
            velocity,
            climbOutFlags,
            movementResult.movementContext().worldState().blockCollisionWorld(),
            movementResult.movementContext().playerDimensionsState())) {
            return null;
        }
        return state.withVelocityAndCollisionFlags(velocity, climbOutFlags);
    }

    private static BedrockCollisionFlags liquidClimbOutFlags(
        BedrockMovementResult movementResult,
        BedrockMovementState state
    ) {
        BedrockCollisionFlags flags = state.collisionFlags();
        if (flags.horizontalCollision() || flags.horizontalBlockContact()) {
            return flags;
        }
        BedrockCollisionFlags tickStartFlags = movementResult.previousState().collisionFlags();
        if (movementResult.previousState().waterTravelFlag()
            && (tickStartFlags.horizontalCollision() || tickStartFlags.horizontalBlockContact())) {
            return tickStartFlags;
        }
        return flags;
    }

    private static Vec3d zeroBlockedHorizontalAxes(Vec3d velocity, BedrockCollisionFlags flags) {
        return new Vec3d(
            flags.xCollision() ? 0.0D : velocity.x(),
            velocity.y(),
            flags.zCollision() ? 0.0D : velocity.z());
    }

    private static boolean liquidTravelActive(BedrockMovementResult movementResult) {
        return movementResult.selectedWaterTravel()
            || movementResult.movementContext().inLava()
            || movementResult.movementContext().liquidMovementMedium() == Medium.LAVA;
    }

    private static void addClimbableVelocities(
            List<BedrockNextTickStateDeriver.DerivedState> states,
            BedrockMovementResult movementResult,
            BedrockMovementState state,
            BedrockMovementState source
    ) {
        List<Double> velocityYBranches = BedrockClimbEndTickVelocityBranches.velocityYBranches(
                movementResult,
                state);
        for (double velocityY : velocityYBranches) {
            addClimbableVelocity(states, movementResult, state, velocityY, source);
        }
    }

    private static void addClimbableVelocity(
            List<BedrockNextTickStateDeriver.DerivedState> states,
            BedrockMovementResult movementResult,
            BedrockMovementState state,
            double velocityY,
            BedrockMovementState source
    ) {
        if (!Double.isFinite(velocityY)) {
            return;
        }

        Vec3d orderedVelocity = state.velocity();
        Vec3d velocity = new Vec3d(orderedVelocity.x(), velocityY, orderedVelocity.z());
        BedrockMovementState nextState = state.withVelocityAndCollisionFlags(velocity, state.collisionFlags());
        states.add(new BedrockNextTickStateDeriver.DerivedState(nextState, source));
    }

    private static void addVelocityY(
            List<BedrockNextTickStateDeriver.DerivedState> states,
            BedrockMovementState state,
            double velocityY,
            BedrockMovementState source
    ) {
        if (!Double.isFinite(velocityY)) {
            return;
        }
        Vec3d velocity = new Vec3d(state.velocity().x(), velocityY, state.velocity().z());
        BedrockMovementState nextState = state.withVelocityAndCollisionFlags(velocity, state.collisionFlags());
        states.add(new BedrockNextTickStateDeriver.DerivedState(nextState, source));
    }

    private static BedrockMovementState withoutSwimHopEvent(BedrockMovementState state) {
        if (!state.collisionFlags().liquidClimbOut()) {
            return state;
        }
        return state.withVelocityAndCollisionFlags(
                state.velocity(),
                state.collisionFlags().withLiquidClimbOut(false));
    }

    private static BedrockMovementState withSwimHopEvent(BedrockMovementState state) {
        if (state.collisionFlags().liquidClimbOut()) {
            return state;
        }
        return state.withVelocityAndCollisionFlags(
                state.velocity(),
                state.collisionFlags().withLiquidClimbOut(true));
    }

    private static void requireBoundedSource(
            List<BedrockNextTickStateDeriver.DerivedState> states,
            int startSize,
            int maxAdded,
            String source
    ) {
        if (states.size() - startSize > maxAdded) {
            throw new IllegalStateException("Bedrock end-tick " + source + " velocities exceeded Java-shaped bound");
        }
    }
}
