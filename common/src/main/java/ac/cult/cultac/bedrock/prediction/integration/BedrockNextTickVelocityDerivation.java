package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.Vec3;

final class BedrockNextTickVelocityDerivation {
    private BedrockNextTickVelocityDerivation() {
    }

    static Set<Vec3> profileStateVelocities(List<BedrockProfileState.Entry> profileStates) {
        LinkedHashSet<Vec3> startingVelocities = new LinkedHashSet<>();
        for (BedrockProfileState.Entry profileState : profileStates) {
            startingVelocities.add(new BedrockStateVelocity(profileState));
        }
        return startingVelocities;
    }

    static Derived fromAcceptedDiff(
        BedrockMovementResult movementResult,
        List<BedrockProfileState.Entry> baseEntries,
        Vec3 acceptedDiff,
        boolean selectedXCollision,
        boolean selectedZCollision,
        boolean canStep
    ) {
        LinkedHashSet<Vec3> startingVelocities = new LinkedHashSet<>();
        LinkedHashMap<BedrockMovementState, BedrockProfileState.Entry> derivedEntries = new LinkedHashMap<>();
        LinkedHashMap<BedrockMovementState, BedrockProfileState.Entry> sourceEntries = sourceEntries(baseEntries);
        Vec3d bedrockAcceptedDiff = BedrockVectorAdapter.toBedrock(acceptedDiff);
        List<BedrockSimulation.NextState> derivedStates =
            BedrockSimulation.deriveNextStates(
                movementResult,
                baseEntries.stream().map(BedrockProfileState.Entry::state).toList(),
                bedrockAcceptedDiff,
                selectedXCollision,
                selectedZCollision,
                canStep);
        for (BedrockSimulation.NextState derivedState : derivedStates) {
            BedrockProfileState.Entry derivedEntry = entryFor(sourceEntries, derivedState);
            derivedEntries.putIfAbsent(derivedState.state(), derivedEntry);
            startingVelocities.add(new BedrockStateVelocity(derivedEntry));
        }
        return new Derived(List.copyOf(derivedEntries.values()), Set.copyOf(startingVelocities));
    }

    private static LinkedHashMap<BedrockMovementState, BedrockProfileState.Entry> sourceEntries(
        List<BedrockProfileState.Entry> baseEntries
    ) {
        LinkedHashMap<BedrockMovementState, BedrockProfileState.Entry> sourceEntries = new LinkedHashMap<>();
        for (BedrockProfileState.Entry entry : baseEntries) {
            sourceEntries.putIfAbsent(entry.state(), entry);
        }
        return sourceEntries;
    }

    private static BedrockProfileState.Entry entryFor(
        LinkedHashMap<BedrockMovementState, BedrockProfileState.Entry> sourceEntries,
        BedrockSimulation.NextState derivedState
    ) {
        BedrockProfileState.Entry sourceEntry = sourceEntries.get(derivedState.lineageState());
        return new BedrockProfileState.Entry(
            derivedState.state(),
            sourceEntry == null ? null : sourceEntry.mobJumpComponent());
    }

    record Derived(List<BedrockProfileState.Entry> entries, Set<Vec3> startingVelocities) {
    }
}
