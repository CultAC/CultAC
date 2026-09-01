package ac.grim.grimac.bedrock.prediction.simulation.postmove;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.grim.grimac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import ac.grim.grimac.bedrock.prediction.simulation.collision.BedrockCollisionSweep;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockLiquidGeometry;
import ac.grim.grimac.bedrock.prediction.world.BlockCollision;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;
import ac.grim.grimac.bedrock.prediction.world.PlacedBlockCollision;

public final class BedrockLiquidClimbOutMovement {
    public static final double CLIMB_OUT_VELOCITY_Y = (double) 0.3F;
    private static final double CLIMB_OUT_FREE_SPACE_Y = 0.6F;

    private BedrockLiquidClimbOutMovement() {
    }

    public static Result apply(
        boolean liquidTravelActive,
        Vec3d previousPosition,
        Vec3d nextPosition,
        Vec3d velocity,
        BedrockCollisionFlags flags,
        BlockCollisionWorld blockCollisionWorld,
        PlayerDimensionsState dimensions
    ) {
        if (!applies(liquidTravelActive, previousPosition, nextPosition, velocity, flags, blockCollisionWorld, dimensions)) {
            return new Result(velocity, flags);
        }
        return new Result(
            new Vec3d(velocity.x(), CLIMB_OUT_VELOCITY_Y, velocity.z()),
            flags.withLiquidClimbOut(true)
        );
    }

    public static boolean applies(
        boolean liquidTravelActive,
        Vec3d previousPosition,
        Vec3d nextPosition,
        Vec3d velocity,
        BedrockCollisionFlags flags,
        BlockCollisionWorld blockCollisionWorld,
        PlayerDimensionsState dimensions
    ) {
        return liquidTravelActive
            && hasHorizontalClimbOutContact(flags)
            && hasFreeClimbOutSpace(previousPosition, nextPosition, velocity, blockCollisionWorld, dimensions);
    }

    private static boolean hasHorizontalClimbOutContact(BedrockCollisionFlags flags) {
        return flags.horizontalCollision() || flags.horizontalBlockContact();
    }

    private static boolean hasFreeClimbOutSpace(
        Vec3d previousPosition,
        Vec3d nextPosition,
        Vec3d velocity,
        BlockCollisionWorld blockCollisionWorld,
        PlayerDimensionsState dimensions
    ) {
        if (blockCollisionWorld.isEmpty()) {
            return true;
        }

        double offsetY = velocity.y() + CLIMB_OUT_FREE_SPACE_Y - nextPosition.y() + previousPosition.y();
        WorldCollisionBox climbOutBox = BedrockCollisionSweep.playerBox(nextPosition, dimensions)
            .move(velocity.x(), offsetY, velocity.z());
        for (BlockCollision obstacle : BedrockCollisionSweep.collisionObstacles(blockCollisionWorld)) {
            if (climbOutBox.intersects(obstacle.box())) {
                return false;
            }
        }
        for (PlacedBlockCollision block : blockCollisionWorld.blocks()) {
            if (liquidClearanceBox(block).intersects(climbOutBox)) {
                return false;
            }
        }
        return true;
    }

    private static WorldCollisionBox liquidClearanceBox(PlacedBlockCollision block) {
        return BedrockLiquidGeometry.liquidBlockBox(block)
            .orElseGet(() -> block.javaStateProperties().waterlogged()
                ? BedrockLiquidGeometry.fullBlockBox(block.position())
                : EMPTY_BOX);
    }

    private static final WorldCollisionBox EMPTY_BOX = new WorldCollisionBox(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);

    public record Result(
        Vec3d velocity,
        BedrockCollisionFlags flags
    ) {
    }
}
