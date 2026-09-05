package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

final class BedrockStartingVelocityProfiles {
    private BedrockStartingVelocityProfiles() {
    }

    static List<BedrockProfileState.Entry> previousEntriesForJavaStartingVelocity(
        SimulationContext context,
        BedrockMovementInputFactory.Input input,
        PredVector javaStartingVelocity
    ) {
        BedrockProfileState.Entry lineageEntry = BedrockProfileState.entryInLineage(javaStartingVelocity);
        if (lineageEntry != null) {
            Vec3d selectedVelocity = BedrockVectorAdapter.toBedrock(javaStartingVelocity);
            List<BedrockProfileState.Entry> matchingEntries = BedrockProfileState.profileEntries(context).stream()
                .filter(entry -> entry.state().velocity().equals(selectedVelocity))
                .map(entry -> entryWithSelectedStartingVelocity(entry, javaStartingVelocity))
                .toList();
            if (!matchingEntries.isEmpty()) {
                return matchingEntries;
            }
            return List.of(entryWithSelectedStartingVelocity(lineageEntry, javaStartingVelocity));
        }
        List<BedrockProfileState.Entry> profileEntries = BedrockProfileState.profileEntries(context);
        if (!profileEntries.isEmpty()) {
            return profileEntries;
        }
        if (javaStartingVelocity instanceof BedrockPredVector candidate) {
            return List.of(new BedrockProfileState.Entry(
                candidate.input().previousState(),
                candidate.input().mobJumpComponent()));
        }
        BedrockMovementState previousState = input.previousState();
        if (javaStartingVelocity == null || javaStartingVelocity.lengthSqr() <= 1.0E-14D) {
            return List.of(new BedrockProfileState.Entry(previousState, input.mobJumpComponent()));
        }
        return List.of(new BedrockProfileState.Entry(
            previousState.withVelocityAndCollisionFlags(
                BedrockVectorAdapter.toBedrock(javaStartingVelocity),
                previousState.collisionFlags()),
            input.mobJumpComponent()));
    }

    static List<PredVector> profileStateVelocities(List<PredVector> input) {
        List<PredVector> output = new ArrayList<>(input.size());
        for (PredVector vector : input) {
            BedrockProfileState.Entry profileEntry = BedrockProfileState.entryInLineage(vector);
            if (profileEntry == null) {
                output.add(vector);
                continue;
            }
            output.add(profileStateVelocity(profileEntry, vector));
        }
        return output;
    }

    private static BedrockProfileState.Entry entryWithSelectedStartingVelocity(
        BedrockProfileState.Entry entry,
        Vec3 javaStartingVelocity
    ) {
        Vec3d velocity = BedrockVectorAdapter.toBedrock(javaStartingVelocity);
        BedrockMovementState state = entry.state();
        BedrockCollisionFlags flags = state.collisionFlags();
        if (velocity.equals(state.velocity()) && flags.equals(state.collisionFlags())) {
            return entry;
        }
        return entry.withState(state.withVelocityAndCollisionFlags(velocity, flags));
    }

    private static BedrockStateVelocity profileStateVelocity(
        BedrockProfileState.Entry profileEntry,
        PredVector vector
    ) {
        BedrockMovementState profileState = profileEntry.state();
        BedrockMovementState nextState = profileState.withVelocityAndCollisionFlags(
            BedrockVectorAdapter.toBedrock(vector),
            profileState.collisionFlags());
        return new BedrockStateVelocity(
            profileEntry.withState(nextState),
            vector);
    }
}
