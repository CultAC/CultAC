package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.HoneySlideState;
import ac.cult.cultac.bedrock.prediction.world.StandingSurfaceState;
import ac.cult.cultac.bedrock.prediction.world.Surface;
import java.util.Objects;

public final class BedrockBlockSurfaceMovement {
    private static final float HONEY_INSIDE_HORIZONTAL_SCALE = 0.4F;
    private static final float HONEY_INSIDE_MIN_VERTICAL_VELOCITY = -0.12F;
    private static final double HONEY_SLIDE_MAX_LOCAL_Y = 0.9375D;
    private static final double HONEY_SLIDE_SIDE_MARGIN = 0.43125D;
    // The vanilla standing-slowdown notification uses abs(y) < 0.1 and
    // abs(y) * 0.2 + 0.4. Java SlimeBlock.stepOn matches this formula.
    private static final float STANDING_SLOWDOWN_VERTICAL_THRESHOLD = 0.1F;
    private static final float STANDING_SLOWDOWN_VERTICAL_SCALE = 0.2F;
    private static final float STANDING_SLOWDOWN_BASE_SCALE = 0.4F;

    private BedrockBlockSurfaceMovement() {
    }

    public static Vec3d applyStandingAfterMove(
        Vec3d currentVelocity,
        Vec3d physicalFeetPosition,
        boolean sneaking,
        boolean onGround,
        BlockCollisionWorld blockCollisionWorld,
        PlayerDimensionsState playerDimensions
    ) {
        return standingSlowdownVelocity(
            currentVelocity,
            sneaking,
            onGround,
            BedrockStandingSurfaceResolver.fromBlockWorld(
                physicalFeetPosition,
                blockCollisionWorld,
                playerDimensions
            )
        );
    }

    public static Vec3d applyKnownStandingSlowdownAfterMove(Vec3d currentVelocity) {
        return standingSlowdownVelocity(currentVelocity);
    }

    public static Vec3d applyInsideBlockAfterPostMoveEffects(
        Vec3d currentVelocity,
        HoneySlideState honeySlideState,
        Vec3d physicalFeetPosition,
        PlayerDimensionsState playerDimensions
    ) {
        return honeyInsideBlockVelocity(
            currentVelocity,
            honeySlideState,
            physicalFeetPosition,
            playerDimensions
        );
    }

    public static Vec3d applyHoneySlideBeforeMove(
        Vec3d currentVelocity,
        HoneySlideState honeySlideState,
        Vec3d physicalFeetPosition,
        PlayerDimensionsState playerDimensions
    ) {
        Objects.requireNonNull(currentVelocity, "currentVelocity");
        if (!honeySlideState.active() || currentVelocity.y() >= HONEY_INSIDE_MIN_VERTICAL_VELOCITY) {
            return currentVelocity;
        }
        if (!intersectsHoneySlideTrigger(honeySlideState, physicalFeetPosition, playerDimensions)) {
            return currentVelocity;
        }
        return honeyFriction(currentVelocity);
    }

    public static boolean isHoneySliding(
        Vec3d currentVelocity,
        HoneySlideState honeySlideState,
        Vec3d physicalFeetPosition,
        PlayerDimensionsState playerDimensions
    ) {
        return honeySlideState.active()
            && currentVelocity.y() < HONEY_INSIDE_MIN_VERTICAL_VELOCITY
            && intersectsHoneySlideTrigger(honeySlideState, physicalFeetPosition, playerDimensions);
    }

    public static Vec3d honeyInsideBlockVelocity(
        Vec3d currentVelocity,
        HoneySlideState honeySlideState,
        Vec3d physicalFeetPosition,
        PlayerDimensionsState playerDimensions
    ) {
        Objects.requireNonNull(currentVelocity, "currentVelocity");
        Objects.requireNonNull(honeySlideState, "honeySlideState");
        Objects.requireNonNull(playerDimensions, "playerDimensions");
        if (!honeySlideState.active()) {
            return currentVelocity;
        }
        int insideHoneyBlocks = honeyInsideBlockCount(honeySlideState, physicalFeetPosition, playerDimensions);
        if (insideHoneyBlocks == 0) {
            return currentVelocity;
        }

        return applyHoneyFrictionCount(currentVelocity, insideHoneyBlocks);
    }

    private static Vec3d applyHoneyFrictionCount(Vec3d currentVelocity, int count) {
        Vec3d velocity = currentVelocity;
        for (int i = 0; i < count; i++) {
            velocity = honeyFriction(velocity);
        }
        return velocity;
    }

    private static Vec3d honeyFriction(Vec3d currentVelocity) {
        float velocityX = (float) currentVelocity.x() * HONEY_INSIDE_HORIZONTAL_SCALE;
        float velocityY = Math.max((float) currentVelocity.y(), HONEY_INSIDE_MIN_VERTICAL_VELOCITY);
        float velocityZ = (float) currentVelocity.z() * HONEY_INSIDE_HORIZONTAL_SCALE;
        return new Vec3d(velocityX, velocityY, velocityZ);
    }

    public static int honeyInsideBlockCount(
        HoneySlideState honeySlideState,
        Vec3d physicalFeetPosition,
        PlayerDimensionsState playerDimensions
    ) {
        WorldCollisionBox actorBox = actorBox(
            physicalFeetPosition,
            honeySlideState.actorWidth() * 0.5D,
            playerDimensions.height()
        );
        // The vanilla block query floors min + 0.001 and max - 0.001 with
        // the default zero inside-block margin.
        int minX = blockAabbMin(actorBox.minX());
        int minY = blockAabbMin(actorBox.minY());
        int minZ = blockAabbMin(actorBox.minZ());
        int maxX = blockAabbMax(actorBox.maxX());
        int maxY = blockAabbMax(actorBox.maxY());
        int maxZ = blockAabbMax(actorBox.maxZ());
        int count = 0;
        for (BlockPosition position : honeySlideState.honeyBlockPositions()) {
            if (insideBlockQuery(position, minX, minY, minZ, maxX, maxY, maxZ)) {
                count++;
            }
        }
        return count;
    }

    private static int blockAabbMin(double coordinate) {
        return (int) Math.floor(coordinate + 0.001D);
    }

    private static int blockAabbMax(double coordinate) {
        return (int) Math.floor(coordinate - 0.001D);
    }

    private static boolean intersectsHoneySlideTrigger(
        HoneySlideState honeySlideState,
        Vec3d physicalFeetPosition,
        PlayerDimensionsState playerDimensions
    ) {
        WorldCollisionBox actorBox = actorBox(
            physicalFeetPosition,
            honeySlideState.actorWidth() * 0.5D,
            playerDimensions.height()
        );
        int minX = blockAabbMin(actorBox.minX());
        int minY = blockAabbMin(actorBox.minY());
        int minZ = blockAabbMin(actorBox.minZ());
        int maxX = blockAabbMax(actorBox.maxX());
        int maxY = blockAabbMax(actorBox.maxY());
        int maxZ = blockAabbMax(actorBox.maxZ());
        for (BlockPosition position : honeySlideState.honeyBlockPositions()) {
            if (insideBlockQuery(position, minX, minY, minZ, maxX, maxY, maxZ)
                && isSlidingDownHoney(position, physicalFeetPosition, honeySlideState.actorWidth())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSlidingDownHoney(
        BlockPosition position,
        Vec3d physicalFeetPosition,
        double actorWidth
    ) {
        if (physicalFeetPosition.y() > position.y() + HONEY_SLIDE_MAX_LOCAL_Y) {
            return false;
        }
        double sideDistance = actorWidth * 0.5D + HONEY_SLIDE_SIDE_MARGIN;
        double dx = Math.abs(position.x() + 0.5D - physicalFeetPosition.x());
        double dz = Math.abs(position.z() + 0.5D - physicalFeetPosition.z());
        return dx > sideDistance || dz > sideDistance;
    }

    private static WorldCollisionBox actorBox(Vec3d physicalFeetPosition, double radius, double actorHeight) {
        return new WorldCollisionBox(
            physicalFeetPosition.x() - radius,
            physicalFeetPosition.y(),
            physicalFeetPosition.z() - radius,
            physicalFeetPosition.x() + radius,
            physicalFeetPosition.y() + actorHeight,
            physicalFeetPosition.z() + radius
        );
    }

    private static boolean insideBlockQuery(
        BlockPosition position,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ
    ) {
        return position.x() >= minX && position.x() <= maxX
            && position.y() >= minY && position.y() <= maxY
            && position.z() >= minZ && position.z() <= maxZ;
    }

    public static Vec3d standingSlowdownVelocity(
        Vec3d currentVelocity,
        boolean sneaking,
        boolean onGround,
        StandingSurfaceState standingSurfaceState
    ) {
        if (!onGround || sneaking || standingSurfaceState == null
            || (!standingSurfaceState.hasSurface(Surface.HONEY)
            && !standingSurfaceState.hasSurface(Surface.SLIME))) {
            return currentVelocity;
        }

        float velocityY = (float) currentVelocity.y();
        if (Math.abs(velocityY) >= STANDING_SLOWDOWN_VERTICAL_THRESHOLD) {
            return currentVelocity;
        }
        return standingSlowdownVelocity(currentVelocity);
    }

    private static Vec3d standingSlowdownVelocity(Vec3d currentVelocity) {
        float velocityY = (float) currentVelocity.y();
        float scale = Math.abs(velocityY) * STANDING_SLOWDOWN_VERTICAL_SCALE + STANDING_SLOWDOWN_BASE_SCALE;
        return new Vec3d(
            (float) currentVelocity.x() * scale,
            currentVelocity.y(),
            (float) currentVelocity.z() * scale
        );
    }
}
