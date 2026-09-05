package ac.cult.cultac.bedrock.prediction.world;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import java.util.Objects;

public record BedrockWorldSnapshot(
        BedrockMovementContext movementContext,
        BlockCollisionWorld blockCollisionWorld,
        PlayerDimensionsState playerDimensionsState,
        BedrockClimbableContact initialClimbableContact,
        PowderSnowContactState initialPowderSnowContact,
        StandingSurfaceState initialStandingSurface,
        BlockMovementSlowdownState initialBlockMovementSlowdownState,
        HoneySlideState honeySlideState
) {
    public BedrockWorldSnapshot {
        movementContext = Objects.requireNonNull(movementContext, "movementContext");
        blockCollisionWorld = Objects.requireNonNull(blockCollisionWorld, "blockCollisionWorld");
        playerDimensionsState = Objects.requireNonNull(playerDimensionsState, "playerDimensionsState");
        initialClimbableContact = Objects.requireNonNull(initialClimbableContact, "initialClimbableContact");
        initialPowderSnowContact = Objects.requireNonNull(initialPowderSnowContact, "initialPowderSnowContact");
        initialStandingSurface = Objects.requireNonNull(initialStandingSurface, "initialStandingSurface");
        initialBlockMovementSlowdownState = Objects.requireNonNull(
                initialBlockMovementSlowdownState,
                "initialBlockMovementSlowdownState");
        honeySlideState = Objects.requireNonNull(honeySlideState, "honeySlideState");
    }

    public static BedrockWorldSnapshot fromContext(BedrockMovementContext context) {
        Objects.requireNonNull(context, "context");
        return new BedrockWorldSnapshot(
                context,
                context.worldState().blockCollisionWorld(),
                context.playerDimensionsState(),
                BedrockClimbableContact.NONE,
                PowderSnowContactState.NONE,
                new StandingSurfaceState(java.util.Set.of()),
                BlockMovementSlowdownState.NONE,
                HoneySlideState.NONE);
    }

    public BedrockClimbableContact climbableContactAt(Vec3d physicalFeetPosition, PlayerDimensionsState dimensions) {
        return BedrockClimbableContact.fromBlockWorld(
                blockCollisionWorld,
                physicalFeetPosition,
                dimensions.width(),
                dimensions.height(),
                movementContext.equipmentState().leatherBoots());
    }

    public BedrockWorldSnapshot withMovementContext(BedrockMovementContext movementContext) {
        return new BedrockWorldSnapshot(
                movementContext,
                blockCollisionWorld,
                playerDimensionsState,
                initialClimbableContact,
                initialPowderSnowContact,
                initialStandingSurface,
                initialBlockMovementSlowdownState,
                honeySlideState);
    }

    public BedrockWorldSnapshot withBlockCollisionWorld(BlockCollisionWorld world) {
        return new BedrockWorldSnapshot(
            movementContext.withBlockCollisionWorld(world),
            world,
            playerDimensionsState,
            initialClimbableContact,
            initialPowderSnowContact,
            initialStandingSurface,
            initialBlockMovementSlowdownState,
            honeySlideState);
    }

    public BedrockWorldSnapshot withPlayerDimensions(PlayerDimensionsState dimensions) {
        return new BedrockWorldSnapshot(
                movementContext.withPlayerDimensions(dimensions),
                blockCollisionWorld,
                dimensions,
                initialClimbableContact,
                initialPowderSnowContact,
                initialStandingSurface,
                initialBlockMovementSlowdownState,
                honeySlideState);
    }

    public BedrockWorldSnapshot withInitialContacts(
            BedrockClimbableContact climbableContact,
            PowderSnowContactState powderSnowContact,
            StandingSurfaceState standingSurface,
            BlockMovementSlowdownState blockMovementSlowdownState,
            HoneySlideState honeySlideState
    ) {
        return new BedrockWorldSnapshot(
                movementContext,
                blockCollisionWorld,
                playerDimensionsState,
                climbableContact,
                powderSnowContact,
                standingSurface,
                blockMovementSlowdownState,
                honeySlideState);
    }
}
