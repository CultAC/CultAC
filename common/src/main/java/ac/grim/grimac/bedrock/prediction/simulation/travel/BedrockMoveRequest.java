package ac.grim.grimac.bedrock.prediction.simulation.travel;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import java.util.Objects;

public record BedrockMoveRequest(
    BedrockResolvedMove resolvedMove,
    Vec3d requestedPosition
) {
    public BedrockMoveRequest {
        resolvedMove = Objects.requireNonNull(resolvedMove, "resolvedMove");
        requestedPosition = Objects.requireNonNull(requestedPosition, "requestedPosition");
    }

    public boolean lavaSwimUpApplied() {
        return resolvedMove.lavaSwimUpApplied();
    }

    public Vec3d move() {
        return resolvedMove.move();
    }

    public Vec3d collisionInputVelocity() {
        return resolvedMove.collisionInputVelocity();
    }
}
