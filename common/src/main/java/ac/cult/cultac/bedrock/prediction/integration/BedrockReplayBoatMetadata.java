package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatProperties;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import java.util.Set;

public record BedrockReplayBoatMetadata(Float width, Float height, Boolean buoyant, Boolean outOfControl,
        Boolean leashed, Boolean gravity, Float baseBuoyancy, Set<String> liquids, Vec3d seat) implements BedrockReplayEvent {
    public BedrockReplayBoatMetadata { liquids = liquids == null ? null : Set.copyOf(liquids); }

    @Override public BedrockMovementState state(BedrockMovementState state) {
        if (!state.isBoat()) return state;
        var old = state.boat().properties();
        var properties = new BedrockBoatProperties(old.runtimeId(), new PlayerDimensionsState(
                width == null ? old.dimensions().width() : width, height == null ? old.dimensions().height() : height),
                buoyant == null ? old.buoyant() : buoyant, gravity == null ? old.gravity() : gravity,
                baseBuoyancy == null ? old.baseBuoyancy() : baseBuoyancy, liquids == null ? old.liquids() : liquids,
                outOfControl == null ? old.outOfControl() : outOfControl,
                leashed == null ? old.leashed() : leashed, seat == null ? old.seat() : seat);
        return state.withBoat(state.boat().withProperties(properties, state.boat().analogPaddles()))
                .withPlayerDimensions(properties.dimensions(), true);
    }
}
