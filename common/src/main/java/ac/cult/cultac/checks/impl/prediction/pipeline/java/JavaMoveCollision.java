package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;

/**
 * The collision of one Entity#move the engine ran forward: the delta Entity#collide received, after the stuck
 * multiplier and Player#maybeBackOffFromEdge, and the movement it returned. The flags are Entity#move's own
 * (1.21.11 Entity.java:735-741): horizontalCollision per axis by Mth#equal, verticalCollision by exact inequality,
 * verticalCollisionBelow when that move went down. The axis results hold the collided movement in the trace's
 * unscaled space, so readers of {@link CollideAxisData} see where the move stopped on each axis it collided on.
 */
public final class JavaMoveCollision extends CollideAxisData {
    private final Vec3 attempted;
    private final Vec3 collided;
    private final boolean collidedX;
    private final boolean collidedZ;
    private final boolean verticalCollision;
    private final boolean verticalCollisionBelow;
    private final List<SimpleCollisionBox> entities;

    private JavaMoveCollision(
            Vec3 attempted,
            Vec3 collided,
            boolean collidedX,
            boolean collidedZ,
            boolean verticalCollision,
            boolean verticalCollisionBelow,
            List<SimpleCollisionBox> entities,
            Vec3 unscaled) {
        super(
                new CollideResult(collidedX, unscaled.x, side(attempted.x)),
                new CollideResult(verticalCollision && !verticalCollisionBelow, unscaled.y),
                new CollideResult(verticalCollisionBelow, unscaled.y),
                new CollideResult(collidedZ, unscaled.z, side(attempted.z)),
                List.of());
        this.attempted = attempted;
        this.collided = collided;
        this.collidedX = collidedX;
        this.collidedZ = collidedZ;
        this.verticalCollision = verticalCollision;
        this.verticalCollisionBelow = verticalCollisionBelow;
        this.entities = entities;
    }

    /** Entity#move's collision for {@code attempted} that Entity#collide returned as {@code collided}. */
    public static JavaMoveCollision of(Vec3 attempted, Vec3 collided, Vec3 stuck, List<SimpleCollisionBox> entities) {
        boolean vertical = attempted.y != collided.y;
        return new JavaMoveCollision(
                attempted,
                collided,
                !CultMath.equal(attempted.x, collided.x),
                !CultMath.equal(attempted.z, collided.z),
                vertical,
                vertical && attempted.y < 0.0D,
                List.copyOf(entities),
                unscale(collided, stuck));
    }

    /** {@code movement} before the stuck multiplier {@code stuck} scaled it; an axis it zeroed stays zero. */
    static Vec3 unscale(Vec3 movement, Vec3 stuck) {
        return new Vec3(
                stuck.x == 0.0D ? 0.0D : movement.x / stuck.x,
                stuck.y == 0.0D ? 0.0D : movement.y / stuck.y,
                stuck.z == 0.0D ? 0.0D : movement.z / stuck.z);
    }

    private static int side(double movement) {
        return movement > 0.0D ? 1 : movement < 0.0D ? -1 : 0;
    }

    /** The delta Entity#collide received. */
    public Vec3 attempted() {
        return attempted;
    }

    /** Entity#collide's movement, before Entity#move's setPos guard. */
    public Vec3 collided() {
        return collided;
    }

    public boolean collidedX() {
        return collidedX;
    }

    public boolean collidedZ() {
        return collidedZ;
    }

    public boolean horizontalCollision() {
        return collidedX || collidedZ;
    }

    public boolean verticalCollision() {
        return verticalCollision;
    }

    public boolean verticalCollisionBelow() {
        return verticalCollisionBelow;
    }

    /** The entity boxes of the position world this move collided with. */
    public List<SimpleCollisionBox> entities() {
        return entities;
    }

    @Override
    public String toString() {
        return "JavaMoveCollision(attempted=" + attempted + ", collided=" + collided + ", x=" + collidedX + ", z="
                + collidedZ + ", vertical=" + verticalCollision + ", below=" + verticalCollisionBelow + ", entities="
                + entities.size() + ")";
    }
}
