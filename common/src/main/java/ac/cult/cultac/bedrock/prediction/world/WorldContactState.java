package ac.cult.cultac.bedrock.prediction.world;

import ac.cult.cultac.bedrock.prediction.model.Medium;
import java.util.Objects;

public record WorldContactState(
    Medium medium,
    FluidState fluidState,
    boolean waterContact,
    boolean lavaContact,
    Medium liquidMovementMedium,
    BlockCollisionWorld blockCollisionWorld
) {
    public WorldContactState(
        Medium medium,
        FluidState fluidState,
        BlockCollisionWorld blockCollisionWorld
    ) {
        this(
            medium,
            fluidState,
            medium == Medium.WATER,
            medium == Medium.LAVA,
            medium,
            blockCollisionWorld
        );
    }

    public WorldContactState(
        Medium medium,
        FluidState fluidState,
        boolean waterContact,
        boolean lavaContact,
        BlockCollisionWorld blockCollisionWorld
    ) {
        this(
            medium,
            fluidState,
            waterContact,
            lavaContact,
            medium,
            blockCollisionWorld
        );
    }

    public WorldContactState {
        medium = Objects.requireNonNull(medium, "medium");
        fluidState = Objects.requireNonNull(fluidState, "fluidState");
        liquidMovementMedium = liquidMovementMedium == null ? Medium.AIR : liquidMovementMedium;
        blockCollisionWorld = Objects.requireNonNull(blockCollisionWorld, "blockCollisionWorld");
    }

    public WorldContactState withBlockCollisionWorld(BlockCollisionWorld world) {
        return new WorldContactState(
            medium, fluidState, waterContact, lavaContact, liquidMovementMedium, world);
    }

}
