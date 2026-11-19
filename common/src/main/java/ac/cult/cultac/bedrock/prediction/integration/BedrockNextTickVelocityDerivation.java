package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.utils.math.Vec3;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class BedrockNextTickVelocityDerivation {
    private BedrockNextTickVelocityDerivation() {}

    static Set<Vec3> profileStateVelocities(List<BedrockProfileState.Entry> profileStates) {
        LinkedHashSet<Vec3> startingVelocities = new LinkedHashSet<>();
        for (BedrockProfileState.Entry profileState : profileStates) {
            startingVelocities.add(new BedrockStateVelocity(profileState));
        }
        return startingVelocities;
    }
}
