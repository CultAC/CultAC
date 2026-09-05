package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.Objects;

public final class BedrockFluidStateResolver {
    private BedrockFluidStateResolver() {
    }

    public static BedrockMovementContext withFluidStateFromBlockWorld(
        BedrockMovementContext context,
        Vec3d physicalFeetPosition
    ) {
        return withFluidStateFromBlockWorld(context, physicalFeetPosition, context.playerDimensionsState());
    }

    public static BedrockMovementContext withFluidStateFromBlockWorld(
        BedrockMovementContext context,
        Vec3d physicalFeetPosition,
        PlayerDimensionsState sensingDimensions
    ) {
        return withFluidStateFromBlockWorld(context, physicalFeetPosition, sensingDimensions, ExistingFluidState.KEEP);
    }

    public static BedrockMovementContext withCurrentTickFluidStateFromBlockWorld(
        BedrockMovementContext context,
        Vec3d physicalFeetPosition,
        PlayerDimensionsState sensingDimensions
    ) {
        return withFluidStateFromBlockWorld(context, physicalFeetPosition, sensingDimensions, ExistingFluidState.REPLACE);
    }

    private static BedrockMovementContext withFluidStateFromBlockWorld(
        BedrockMovementContext context,
        Vec3d physicalFeetPosition,
        PlayerDimensionsState sensingDimensions,
        ExistingFluidState existingFluidState
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(physicalFeetPosition, "physicalFeetPosition");
        Objects.requireNonNull(sensingDimensions, "sensingDimensions");
        WorldContactState world = context.worldState();
        if (existingFluidState == ExistingFluidState.KEEP && world.fluidState() != FluidState.NONE) {
            return context;
        }
        // Start-of-tick liquid sensing must be derived from the trusted previous
        // feet position and block world, before the packet destination is applied.
        BedrockFluidResolution resolution = BedrockBlockFluidResolver.resolve(
            world.blockCollisionWorld(),
            BedrockLiquidGeometry.playerBox(physicalFeetPosition, sensingDimensions)
        );
        if (resolution == BedrockFluidResolution.NONE) {
            return withWorldState(context, worldWithFluid(
                world,
                Medium.AIR,
                FluidState.NONE,
                false,
                false,
                Medium.AIR
            ));
        }
        FluidState fluidState = withActorSwimming(resolution.fluidState(), world.fluidState().actorSwimming());
        return withWorldState(context, worldWithFluid(
            world,
            resolution.medium(),
            fluidState,
            resolution.waterContact(),
            resolution.lavaContact(),
            resolution.liquidMovementMedium()
        ));
    }

    private static BedrockMovementContext withWorldState(
        BedrockMovementContext context,
        WorldContactState world
    ) {
        return new BedrockMovementContext(
            context.effectState(),
            context.attributeState(),
            world,
            context.equipmentState(),
            context.entityContactState(),
            context.modifierState(),
            context.playerDimensionsState()
        );
    }

    private static WorldContactState worldWithFluid(
        WorldContactState world,
        Medium medium,
        FluidState fluidState,
        boolean waterContact,
        boolean lavaContact,
        Medium liquidMovementMedium
    ) {
        return new WorldContactState(
            medium,
            fluidState,
            waterContact,
            lavaContact,
            liquidMovementMedium,
            world.blockCollisionWorld()
        );
    }

    private static FluidState withActorSwimming(FluidState fluidState, boolean actorSwimming) {
        return new FluidState(
            fluidState.current(),
            fluidState.currentPositiveX(),
            fluidState.currentNegativeX(),
            fluidState.currentPositiveZ(),
            fluidState.currentNegativeZ(),
            fluidState.bubbleColumnUp(),
            fluidState.bubbleColumnDown(),
            fluidState.currentState(),
            fluidState.bubbleColumnState(),
            fluidState.waterWalkOnGroundComponentPresent(),
            fluidState.swimSpeedMultiplier(),
            actorSwimming
        );
    }

    private enum ExistingFluidState {
        KEEP,
        REPLACE
    }

}
