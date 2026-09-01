package ac.grim.grimac.bedrock.prediction.simulation.collision;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import ac.grim.grimac.bedrock.prediction.world.BlockCollision;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;
import java.util.List;
import java.util.Objects;

public final class BedrockBlockCollisionResolver {
    public static final double PLAYER_RADIUS = 0.30000001192092896D;
    private static final double EPSILON = BedrockCollisionSweep.EPSILON;
    private static final double CONTACT_EPSILON = BedrockCollisionSweep.CONTACT_EPSILON;
    private static final double MAX_FLOAT_CONTACT_EPSILON = 1.0E-3D;

    private BedrockBlockCollisionResolver() {
    }

    public static Result fromMove(
        Vec3d requestedDelta,
        Vec3d velocity,
        List<BlockCollision> obstacles,
        PlayerDimensionsState playerDimensions,
        BedrockCollisionSweep.MoveResult baseMove,
        BedrockCollisionSweep.MoveResult selectedMove,
        boolean steppedUp
    ) {
        Objects.requireNonNull(playerDimensions, "playerDimensions");
        boolean xCollision = selectedMove.xCollision();
        boolean yCollision = selectedMove.yCollision();
        boolean zCollision = selectedMove.zCollision();

        Vec3d nextVelocity = velocity;
        if (xCollision) {
            nextVelocity = new Vec3d(0.0D, nextVelocity.y(), nextVelocity.z());
        }
        if (yCollision) {
            nextVelocity = new Vec3d(nextVelocity.x(), 0.0D, nextVelocity.z());
        }
        if (zCollision) {
            nextVelocity = new Vec3d(nextVelocity.x(), nextVelocity.y(), 0.0D);
        }

        boolean verticalCollision = yCollision;

        boolean onGround = yCollision && requestedDelta.y() < 0.0D;

        boolean horizontalCollisionFlag = selectedMove.horizontalCollision();
        boolean horizontalBlockContact = horizontalCollisionFlag
            || hasHorizontalBlockContact(selectedMove.position(), obstacles, playerDimensions)
            || steppedUp && hasHorizontalBlockContact(baseMove.position(), obstacles, playerDimensions);
        return new Result(
            selectedMove.position(),
            nextVelocity,
            xCollision,
            zCollision,
            selectedMove.horizontalCollision(),
            horizontalCollisionFlag,
            horizontalBlockContact,
            verticalCollision,
            steppedUp,
            onGround
        );
    }

    public static boolean hasFloorContact(
        Vec3d feet,
        BlockCollisionWorld world,
        double radius
    ) {
        if (world.isEmpty()) {
            return false;
        }
        WorldCollisionBox footprint = new WorldCollisionBox(
            feet.x() - radius,
            feet.y(),
            feet.z() - radius,
            feet.x() + radius,
            feet.y() + EPSILON,
            feet.z() + radius
        );
        for (BlockCollision obstacle : BedrockCollisionSweep.collisionObstacles(world)) {
            if (Math.abs(obstacle.box().maxY() - feet.y()) <= EPSILON && footprint.overlapsXz(obstacle.box())) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasHorizontalBlockContact(
        Vec3d feet,
        List<BlockCollision> obstacles,
        PlayerDimensionsState playerDimensions
    ) {
        HorizontalContactAxes axes = horizontalContactAxes(feet, obstacles, playerDimensions);
        return axes.xContact() || axes.zContact();
    }

    public static HorizontalContactAxes horizontalContactAxes(
        Vec3d feet,
        List<BlockCollision> obstacles,
        PlayerDimensionsState playerDimensions
    ) {
        double epsilon = horizontalContactEpsilon(feet);
        WorldCollisionBox actorBox = BedrockCollisionSweep.playerBox(feet, playerDimensions);
        boolean xContact = false;
        boolean zContact = false;
        for (BlockCollision obstacle : obstacles) {
            WorldCollisionBox box = obstacle.box();
            if (!overlapsY(actorBox, box)) {
                continue;
            }
            if (actorBox.intersects(box)) {
                return new HorizontalContactAxes(true, true);
            }
            if (overlapsZ(actorBox, box)
                && (Math.abs(actorBox.maxX() - box.minX()) <= epsilon
                || Math.abs(actorBox.minX() - box.maxX()) <= epsilon)) {
                xContact = true;
            }
            if (overlapsX(actorBox, box)
                && (Math.abs(actorBox.maxZ() - box.minZ()) <= epsilon
                || Math.abs(actorBox.minZ() - box.maxZ()) <= epsilon)) {
                zContact = true;
            }
        }
        return new HorizontalContactAxes(xContact, zContact);
    }

    public static HorizontalContactAxes movementSideHorizontalContactAxes(
        Vec3d feet,
        Vec3d movement,
        List<BlockCollision> obstacles,
        PlayerDimensionsState playerDimensions
    ) {
        double epsilon = horizontalContactEpsilon(feet);
        WorldCollisionBox actorBox = BedrockCollisionSweep.playerBox(feet, playerDimensions);
        boolean positiveX = movement.x() > EPSILON;
        boolean negativeX = movement.x() < -EPSILON;
        boolean positiveZ = movement.z() > EPSILON;
        boolean negativeZ = movement.z() < -EPSILON;
        boolean xContact = false;
        boolean zContact = false;
        for (BlockCollision obstacle : obstacles) {
            WorldCollisionBox box = obstacle.box();
            if (!overlapsY(actorBox, box)) {
                continue;
            }
            if (overlapsZ(actorBox, box)
                && ((positiveX && Math.abs(actorBox.maxX() - box.minX()) <= epsilon)
                || (negativeX && Math.abs(actorBox.minX() - box.maxX()) <= epsilon))) {
                xContact = true;
            }
            if (overlapsX(actorBox, box)
                && ((positiveZ && Math.abs(actorBox.maxZ() - box.minZ()) <= epsilon)
                || (negativeZ && Math.abs(actorBox.minZ() - box.maxZ()) <= epsilon))) {
                zContact = true;
            }
        }
        return new HorizontalContactAxes(xContact, zContact);
    }

    public static double horizontalContactEpsilon(Vec3d feet) {
        double xUlp = Math.ulp((float) feet.x());
        double zUlp = Math.ulp((float) feet.z());
        return Math.min(MAX_FLOAT_CONTACT_EPSILON, Math.max(CONTACT_EPSILON, 4.0D * Math.max(xUlp, zUlp)));
    }

    private static boolean overlapsY(WorldCollisionBox left, WorldCollisionBox right) {
        return left.maxY() > right.minY() && left.minY() < right.maxY();
    }

    private static boolean overlapsX(WorldCollisionBox left, WorldCollisionBox right) {
        return left.maxX() > right.minX() && left.minX() < right.maxX();
    }

    private static boolean overlapsZ(WorldCollisionBox left, WorldCollisionBox right) {
        return left.maxZ() > right.minZ() && left.minZ() < right.maxZ();
    }

    public record Result(
        Vec3d position,
        Vec3d velocity,
        boolean xCollision,
        boolean zCollision,
        boolean horizontalCollision,
        boolean horizontalCollisionFlag,
        boolean horizontalBlockContact,
        boolean verticalCollision,
        boolean steppedUp,
        boolean onGround
    ) {
    }

    public record HorizontalContactAxes(boolean xContact, boolean zContact) {
    }
}
