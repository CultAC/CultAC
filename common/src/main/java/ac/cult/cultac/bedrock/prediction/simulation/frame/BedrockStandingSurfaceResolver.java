package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockStandingBlockResolver;
import ac.cult.cultac.bedrock.prediction.world.BedrockBlockFriction;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.StandingSurfaceState;
import ac.cult.cultac.bedrock.prediction.world.Surface;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

final class BedrockStandingSurfaceResolver {
    // The vanilla standing-support helper lowers the actor AABB by 0.2
    // before selecting support.
    private BedrockStandingSurfaceResolver() {
    }

    public static StandingSurfaceState fromBlockWorld(
        Vec3d physicalFeetPosition,
        BlockCollisionWorld blockWorld
    ) {
        return fromBlockWorld(physicalFeetPosition, blockWorld, PlayerDimensionsState.DEFAULT);
    }

    public static StandingSurfaceState fromBlockWorld(
        Vec3d physicalFeetPosition,
        BlockCollisionWorld blockWorld,
        PlayerDimensionsState playerDimensions
    ) {
        Objects.requireNonNull(physicalFeetPosition, "physicalFeetPosition");
        Objects.requireNonNull(blockWorld, "blockWorld");
        Objects.requireNonNull(playerDimensions, "playerDimensions");
        java.util.Optional<PlacedBlockCollision> standingBlock = standingBlock(physicalFeetPosition, blockWorld, playerDimensions);
        return standingBlock
            .map(BedrockStandingSurfaceResolver::fromStandingBlock)
            .orElseGet(() -> new StandingSurfaceState(Set.of()));
    }

    static boolean hasCollisionSupport(
        Vec3d physicalFeetPosition,
        BlockCollisionWorld blockWorld,
        PlayerDimensionsState playerDimensions
    ) {
        Objects.requireNonNull(physicalFeetPosition, "physicalFeetPosition");
        Objects.requireNonNull(blockWorld, "blockWorld");
        Objects.requireNonNull(playerDimensions, "playerDimensions");
        return currentlyStandingOn(physicalFeetPosition, blockWorld, playerDimensions).isPresent();
    }

    public static StandingSurfaceState resolve(
        boolean onGround,
        Vec3d physicalFeetPosition,
        BlockCollisionWorld blockWorld
    ) {
        if (!onGround) {
            return new StandingSurfaceState(Set.of());
        }
        return fromBlockWorld(physicalFeetPosition, blockWorld);
    }

    static StandingSurfaceState travelSurfaceFromBlockWorld(
        Vec3d physicalFeetPosition,
        BlockCollisionWorld blockWorld
    ) {
        return travelSurfaceFromBlockWorld(physicalFeetPosition, blockWorld, PlayerDimensionsState.DEFAULT);
    }

    static StandingSurfaceState travelSurfaceFromBlockWorld(
        Vec3d physicalFeetPosition,
        BlockCollisionWorld blockWorld,
        PlayerDimensionsState playerDimensions
    ) {
        Objects.requireNonNull(physicalFeetPosition, "physicalFeetPosition");
        Objects.requireNonNull(blockWorld, "blockWorld");
        Objects.requireNonNull(playerDimensions, "playerDimensions");
        BlockPosition travelBlock = new BlockPosition(
            floorBlockCoordinate(physicalFeetPosition.x()),
            floorBlockCoordinate(physicalFeetPosition.y() - 0.1D),
            floorBlockCoordinate(physicalFeetPosition.z())
        );
        return blockWorld.blockAt(travelBlock)
            .map(BedrockStandingSurfaceResolver::fromStandingBlock)
            .orElseGet(() -> new StandingSurfaceState(Set.of()));
    }

    private static java.util.Optional<PlacedBlockCollision> standingBlock(
        Vec3d physicalFeetPosition,
        BlockCollisionWorld blockWorld,
        PlayerDimensionsState playerDimensions
    ) {
        java.util.Optional<PlacedBlockCollision> supportBlock = currentlyStandingOn(
            physicalFeetPosition,
            blockWorld,
            playerDimensions
        );
        return supportBlock.isPresent() ? supportBlock : floorBlock(physicalFeetPosition, blockWorld);
    }

    private static java.util.Optional<PlacedBlockCollision> floorBlock(
        Vec3d physicalFeetPosition,
        BlockCollisionWorld blockWorld
    ) {
        BlockPosition standingBlock = new BlockPosition(
            floorBlockCoordinate(physicalFeetPosition.x()),
            floorBlockCoordinate(physicalFeetPosition.y() - BedrockStandingBlockResolver.QUERY_Y_OFFSET),
            floorBlockCoordinate(physicalFeetPosition.z())
        );
        return blockWorld.blockAt(standingBlock);
    }

    private static java.util.Optional<PlacedBlockCollision> currentlyStandingOn(
        Vec3d physicalFeetPosition,
        BlockCollisionWorld blockWorld,
        PlayerDimensionsState playerDimensions
    ) {
        return BedrockStandingBlockResolver.resolve(
            physicalFeetPosition, blockWorld, playerDimensions
        ).map(BedrockStandingBlockResolver.StandingSupport::block);
    }

    private static StandingSurfaceState fromStandingBlock(PlacedBlockCollision block) {
        EnumSet<Surface> surfaces = EnumSet.noneOf(Surface.class);
        if (block.hasContactBehavior(BlockContactBehavior.HONEY)) {
            surfaces.add(Surface.HONEY);
        }
        if (block.hasContactBehavior(BlockContactBehavior.SLIME)) {
            surfaces.add(Surface.SLIME);
        }
        if (block.hasContactBehavior(BlockContactBehavior.SOUL_SAND)) {
            surfaces.add(Surface.SOUL_SAND);
        }
        if (block.hasContactBehavior(BlockContactBehavior.SOUL_SOIL)) {
            surfaces.add(Surface.SOUL_SOIL);
        }
        return new StandingSurfaceState(
            surfaces.isEmpty() ? Set.of() : Set.copyOf(surfaces),
            BedrockBlockFriction.from(block)
        );
    }

    private static int floorBlockCoordinate(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("standing surface coordinate must be finite");
        }
        return (int) Math.floor(value);
    }

}
