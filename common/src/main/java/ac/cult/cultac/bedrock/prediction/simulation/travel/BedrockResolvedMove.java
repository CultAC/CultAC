package ac.cult.cultac.bedrock.prediction.simulation.travel;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import java.util.Objects;

public record BedrockResolvedMove(
    boolean lavaSwimUpApplied,
    Vec3d move,
    Vec3d collisionInputVelocity
) {
    public BedrockResolvedMove {
        move = Objects.requireNonNull(move, "move");
        collisionInputVelocity = Objects.requireNonNull(collisionInputVelocity, "collisionInputVelocity");
    }
}
