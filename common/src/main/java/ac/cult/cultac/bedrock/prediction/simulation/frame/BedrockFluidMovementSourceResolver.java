package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.api.BedrockFluidMovementSource;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.FluidCurrentState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import java.util.Objects;

public final class BedrockFluidMovementSourceResolver {
    private BedrockFluidMovementSourceResolver() {
    }

    public static BedrockFluidMovementSource fromContext(BedrockMovementContext context, double feetY) {
        Objects.requireNonNull(context, "context");
        return fromResolvedFluid(
            context.worldState().medium(),
            context.worldState().fluidState(),
            context,
            feetY
        );
    }

    private static BedrockFluidMovementSource fromResolvedFluid(
        Medium medium,
        FluidState fluidState,
        BedrockMovementContext context,
        double feetY
    ) {
        FluidCurrentState currentState = fluidState.currentState();
        if (!currentState.appliesAtFeetY(feetY)) {
            return BedrockFluidMovementSource.NONE;
        }
        double x = currentState.directionX();
        double y = currentState.directionY();
        double z = currentState.directionZ();
        if (!currentState.hasDirectionVector()) {
            if (fluidState.currentPositiveX() || context.waterCurrentPositiveX()) {
                x += 1.0D;
            }
            if (fluidState.currentNegativeX() || context.waterCurrentNegativeX()) {
                x -= 1.0D;
            }
            if (fluidState.currentPositiveZ() || context.waterCurrentPositiveZ()) {
                z += 1.0D;
            }
            if (fluidState.currentNegativeZ() || context.waterCurrentNegativeZ()) {
                z -= 1.0D;
            }
        }
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length < 0.0001D) {
            return BedrockFluidMovementSource.NONE;
        }
        double push = currentState.pushPerTick();
        Vec3d direction = new Vec3d(x / length, y / length, z / length);
        Vec3d appliedDelta = new Vec3d(direction.x() * push, direction.y() * push, direction.z() * push);
        return new BedrockFluidMovementSource(
            medium,
            direction,
            push,
            appliedDelta
        );
    }
}
