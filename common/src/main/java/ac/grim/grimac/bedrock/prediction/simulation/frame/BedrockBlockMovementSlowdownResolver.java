package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.grim.grimac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;
import ac.grim.grimac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;
import ac.grim.grimac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.Objects;

public final class BedrockBlockMovementSlowdownResolver {
    // vanilla contracts every face by 0.001F when its zero-margin entity-inside scan enumerates blocks.
    private static final double ENTITY_INSIDE_AABB_CONTRACTION = (double) 0.001F;

    private BedrockBlockMovementSlowdownResolver() {
    }

    public static BlockMovementSlowdownState nextState(
        BedrockMovementContext context,
        Vec3d nextPosition,
        PlayerDimensionsState committedMovementDimensions
    ) {
        // vanilla entity-inside slowdown is resolved from the post-move actor AABB, not the swept path.
        return fromBlockWorld(
            committedMovementDimensions.width(),
            committedMovementDimensions.height(),
            nextPosition,
            context.worldState().blockCollisionWorld(),
            context.effectState().weaving()
        );
    }

    public static BlockMovementSlowdownState nextStateFromSweptMove(
        BedrockMovementContext context,
        Vec3d previousPosition,
        Vec3d nextPosition,
        PlayerDimensionsState committedMovementDimensions
    ) {
        Objects.requireNonNull(previousPosition, "previousPosition");
        Objects.requireNonNull(nextPosition, "nextPosition");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(committedMovementDimensions, "committedMovementDimensions");
        return fromActorBox(
            sweptActorBox(
                committedMovementDimensions.width(),
                committedMovementDimensions.height(),
                previousPosition,
                nextPosition
            ),
            context.worldState().blockCollisionWorld(),
            context.effectState().weaving()
        );
    }

    public static BlockMovementSlowdownState fromBlockWorld(
        double actorWidth,
        double actorHeight,
        Vec3d physicalFeetPosition,
        BlockCollisionWorld blockWorld,
        boolean weaving
    ) {
        Objects.requireNonNull(physicalFeetPosition, "physicalFeetPosition");
        Objects.requireNonNull(blockWorld, "blockWorld");
        WorldCollisionBox actorBox = actorBox(actorWidth, actorHeight, physicalFeetPosition);
        return fromActorBox(actorBox, blockWorld, weaving);
    }

    private static BlockMovementSlowdownState fromActorBox(
        WorldCollisionBox actorBox,
        BlockCollisionWorld blockWorld,
        boolean weaving
    ) {
        boolean cobweb = false;
        boolean sweetBerryBush = false;
        boolean powderSnow = false;
        for (PlacedBlockCollision block : blockWorld.blocks()) {
            if (!contactBoxIntersectsActor(block, actorBox)) {
                continue;
            }
            if (block.hasContactBehavior(BlockContactBehavior.COBWEB)) {
                cobweb = true;
            } else if (block.hasContactBehavior(BlockContactBehavior.SWEET_BERRY_BUSH)) {
                sweetBerryBush = true;
            } else if (block.hasContactBehavior(BlockContactBehavior.POWDER_SNOW)) {
                powderSnow = true;
            }
        }

        BlockMovementSlowdownState slowdownState = BlockMovementSlowdownState.NONE;
        if (cobweb) {
            slowdownState = BlockMovementSlowdownState.combine(
                slowdownState,
                weaving && !sweetBerryBush && !powderSnow
                    ? BlockMovementSlowdownState.WEAVING_COBWEB
                    : BlockMovementSlowdownState.COBWEB
            );
        }
        if (sweetBerryBush) {
            slowdownState = BlockMovementSlowdownState.combine(
                slowdownState,
                BlockMovementSlowdownState.SWEET_BERRY_BUSH
            );
        }
        if (powderSnow) {
            slowdownState = BlockMovementSlowdownState.combine(
                slowdownState,
                BlockMovementSlowdownState.POWDER_SNOW
            );
        }
        return slowdownState;
    }

    private static boolean contactBoxIntersectsActor(PlacedBlockCollision block, WorldCollisionBox actorBox) {
        for (WorldCollisionBox box : block.insideBlockContactBoxes()) {
            if (box.intersects(actorBox)) {
                return true;
            }
        }
        return false;
    }

    private static WorldCollisionBox actorBox(double actorWidth, double actorHeight, Vec3d physicalFeetPosition) {
        if (!Double.isFinite(actorWidth) || actorWidth < 0.0D) {
            throw new IllegalArgumentException("block movement slowdown actor width must be finite and non-negative");
        }
        if (!Double.isFinite(actorHeight) || actorHeight < 0.0D) {
            throw new IllegalArgumentException("block movement slowdown actor height must be finite and non-negative");
        }
        double radius = actorWidth * 0.5D;
        return new WorldCollisionBox(
            physicalFeetPosition.x() - radius + ENTITY_INSIDE_AABB_CONTRACTION,
            physicalFeetPosition.y() + ENTITY_INSIDE_AABB_CONTRACTION,
            physicalFeetPosition.z() - radius + ENTITY_INSIDE_AABB_CONTRACTION,
            physicalFeetPosition.x() + radius - ENTITY_INSIDE_AABB_CONTRACTION,
            physicalFeetPosition.y() + actorHeight - ENTITY_INSIDE_AABB_CONTRACTION,
            physicalFeetPosition.z() + radius - ENTITY_INSIDE_AABB_CONTRACTION
        );
    }

    private static WorldCollisionBox sweptActorBox(
        double actorWidth,
        double actorHeight,
        Vec3d previousPosition,
        Vec3d nextPosition
    ) {
        WorldCollisionBox previous = actorBox(actorWidth, actorHeight, previousPosition);
        WorldCollisionBox next = actorBox(actorWidth, actorHeight, nextPosition);
        return new WorldCollisionBox(
            Math.min(previous.minX(), next.minX()),
            Math.min(previous.minY(), next.minY()),
            Math.min(previous.minZ(), next.minZ()),
            Math.max(previous.maxX(), next.maxX()),
            Math.max(previous.maxY(), next.maxY()),
            Math.max(previous.maxZ(), next.maxZ())
        );
    }
}
