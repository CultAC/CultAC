package ac.grim.grimac.checks.impl.prediction.stage.uncertainty;

import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.stage.UncertaintyPipeline;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.CollideAxisData;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static ac.grim.grimac.utils.math.VectorUtils.LARGE_MOVEMENT;

// There are many things that can affect a user's movement
// They range from blocks pushing the user out of them, to pistons pushing a user
@ToString
public class ValidMovements {
    PredVector initialStartingVel;
    transient GrimPlayer player;
    @Getter
    PredictionResult result;
    PredictionResult lastResult;
    @Getter
    @Setter
    SimpleCollisionBox collisionIgnoredMaxStartingVelExtents = null;
    @Getter
    @Setter
    Vec3 closestToTarget = null;
    @Getter
    MovementTrace closestTrace = null;
    @Getter
    @Setter
    boolean canStep;
    @Getter
    @Setter
    boolean controlsVerticalMovement;
    @Getter
    @Setter
    boolean didTickSkipGravity;
    private final List<UncertaintyHandler> uncertaintyHandlers;

    public ValidMovements(PredVector initialStartingVel, GrimPlayer player, PredictionResult result, boolean canStep) {
        this(initialStartingVel, player, result, canStep, UncertaintyPipeline.MODIFIERS);
    }

    public ValidMovements(PredVector initialStartingVel, GrimPlayer player, PredictionResult result, boolean canStep, List<UncertaintyHandler> uncertaintyHandlers) {
        this.initialStartingVel = initialStartingVel;
        this.player = player;
        this.result = result;
        this.canStep = canStep;
        this.uncertaintyHandlers = uncertaintyHandlers;

        result.setValidMovements(this);
    }

    public Vec3 transformTowards(Vec3 end, PredictionResult lastResult) {
        return transformTraceTowards(end, lastResult).position();
    }

    public MovementTrace transformTraceTowards(Vec3 end, PredictionResult lastResult) {
        // Vectors are immutable, so we need to make a copy
        MovementTrace trace = MovementTrace.start(initialStartingVel);

        for (UncertaintyHandler handler : uncertaintyHandlers) {
            trace = handler.handleMovementTrace(player, result.getValidMovements(), result, result.getSimulationContext(), lastResult, trace, end);
        }

        return trace;
    }

    public ValidMovements computeExtents(PredictionResult lastResult) {
        // closestToTarget will call the AABB, so just figure it out now. Also cache it, to prevent infinite recursion to the previous movement.
        SimpleCollisionBox vectorExtents = initialStartingVel.collisionIgnoredExtents(result.getTarget());
        this.collisionIgnoredMaxStartingVelExtents = vectorExtents == null ? computeAABB(lastResult) : vectorExtents;
        return this;
    }

    public ValidMovements computeClosest(PredictionResult lastResult) {
        this.closestTrace = transformTraceTowards(result.getTarget(), lastResult);
        this.closestToTarget = closestTrace.position();
        return this;
    }

    public Vec3 computeCollisionIgnoredMovement(PredictionResult lastResult) {
        // Select one complete movement with the existing uncertainty handlers.
        // In particular, Fireworks must share its radius between Y and X/Z.
        result.setCollideAxisData(new CollideAxisData(
                new CollideAxisData.CollideResult(false, 0), null, null,
                new CollideAxisData.CollideResult(false, 0), new java.util.ArrayList<>()));
        computeClosest(lastResult);
        return closestToTarget;
    }

    public void applyCollisionsToSelectedMovement(PredictionResult lastResult) {
        // Reuse the selected movement: running the uncertainty handlers again
        // after clipping would grant their allowance a second time.
        MovementTrace movement = closestTrace;
        Vec3 base = movement.collisionBaseOffset();
        if (base.lengthSqr() > 1.0E-14) {
            movement = movement.withPosition(movement.position().with(
                    movement.position().subtract(base), "external movement collision base removed"));
        }
        setClosestTrace(new CollisionModifier().handleMovementTrace(
                player, this, result, result.getSimulationContext(), lastResult, movement, result.getTarget()));
    }

    public Vec3 getPositionOnlyDelta() {
        return closestTrace == null ? Vec3.ZERO : closestTrace.positionOnlyDelta();
    }

    protected void setClosestTrace(MovementTrace closestTrace) {
        this.closestTrace = closestTrace;
        this.closestToTarget = closestTrace == null ? null : closestTrace.position();
    }

    protected PredVector initialStartingVelocity() {
        return initialStartingVel;
    }

    public SimpleCollisionBox computeAABB(PredictionResult lastResult) {
        double posX = transformTowards(initialStartingVel.withX(LARGE_MOVEMENT), lastResult).x;
        double negX = transformTowards(initialStartingVel.withX(-LARGE_MOVEMENT), lastResult).x;
        double posY = transformTowards(initialStartingVel.withY(LARGE_MOVEMENT), lastResult).y;
        double negY = transformTowards(initialStartingVel.withY(-LARGE_MOVEMENT), lastResult).y;
        double posZ = transformTowards(initialStartingVel.withZ(LARGE_MOVEMENT), lastResult).z;
        double negZ = transformTowards(initialStartingVel.withZ(-LARGE_MOVEMENT), lastResult).z;

        return new SimpleCollisionBox(negX, negY, negZ, posX, posY, posZ).sort();
    }

    public boolean isTestingMaxStartingVelExtents(Vec3 end) {
        return Math.abs(end.x) == LARGE_MOVEMENT ||
                Math.abs(end.y) == LARGE_MOVEMENT ||
                Math.abs(end.z) == LARGE_MOVEMENT;
    }

    public void addTransformers(UncertaintyPipeline uncertaintyPipeline) {

    }
}
