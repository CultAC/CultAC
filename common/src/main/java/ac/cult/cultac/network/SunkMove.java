package ac.cult.cultac.network;

import ac.cult.cultac.protocol.codec.entity.MoveEntityCodec;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.ApiStatus;

/**
 * One connection's values of a move its encoder read as {@link MoveEntityCodec#SUNK}: the components of the
 * {@link ClientboundMoveEntity} the read would have returned, with a {@link EntityDelta.Linear} delta as {@link #x},
 * {@link #y} and {@link #z}, or a {@link EntityDelta.Stepped} one as {@link #steps} steps. Held from the read until its
 * dispatch returns; one at a time. Model context only.
 */
@ApiStatus.Internal
public final class SunkMove {
    /** The most steps a stepped move may have to be held. */
    static final int MAX_STEPS = 16;

    boolean held;
    private int entityId;
    private short x, y, z;
    private int steps;
    private final short[] stepX = new short[MAX_STEPS];
    private final short[] stepY = new short[MAX_STEPS];
    private final short[] stepZ = new short[MAX_STEPS];
    private final int[] stepTicks = new int[MAX_STEPS];
    private float yaw, pitch;
    private boolean onGround, hasPosition, hasRotation;

    void step(int index, short x, short y, short z, int ticks) {
        stepX[index] = x;
        stepY[index] = y;
        stepZ[index] = z;
        stepTicks[index] = ticks;
    }

    void holdStepped(int entityId, int steps, float yaw, float pitch, boolean onGround, boolean hasRotation) {
        hold(entityId, (short) 0, (short) 0, (short) 0, yaw, pitch, onGround, true, hasRotation);
        this.steps = steps;
    }

    void hold(
            int entityId,
            short x,
            short y,
            short z,
            float yaw,
            float pitch,
            boolean onGround,
            boolean hasPosition,
            boolean hasRotation) {
        this.entityId = entityId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
        this.hasPosition = hasPosition;
        this.hasRotation = hasRotation;
        steps = 0;
        held = true;
    }

    /** Ends the hold the read began, once nothing dispatches its move any more. */
    public void release() {
        held = false;
    }

    /** Whether a read sank a move whose dispatch has not returned. */
    public boolean held() {
        return held;
    }

    private void requireHeld() {
        if (!held) throw new IllegalStateException("No sunk move is held");
    }

    public int entityId() {
        requireHeld();
        return entityId;
    }

    public short x() {
        requireHeld();
        return x;
    }

    public short y() {
        requireHeld();
        return y;
    }

    public short z() {
        requireHeld();
        return z;
    }

    public float yaw() {
        requireHeld();
        return yaw;
    }

    public float pitch() {
        requireHeld();
        return pitch;
    }

    public boolean onGround() {
        requireHeld();
        return onGround;
    }

    public boolean hasPosition() {
        requireHeld();
        return hasPosition;
    }

    public boolean hasRotation() {
        requireHeld();
        return hasRotation;
    }

    /** The number of steps of a stepped move; 0 for a linear one. */
    public int steps() {
        requireHeld();
        return steps;
    }

    public short stepX(int index) {
        requireStep(index);
        return stepX[index];
    }

    public short stepY(int index) {
        requireStep(index);
        return stepY[index];
    }

    public short stepZ(int index) {
        requireStep(index);
        return stepZ[index];
    }

    public int stepTicks(int index) {
        requireStep(index);
        return stepTicks[index];
    }

    private void requireStep(int index) {
        requireHeld();
        Objects.checkIndex(index, steps);
    }

    /** The packet the read would have returned. */
    public ClientboundMoveEntity packet() {
        requireHeld();
        if (steps != 0) {
            var result = new EntityDelta.Step[steps];
            for (int i = 0; i < steps; i++)
                result[i] = new EntityDelta.Step(stepX[i], stepY[i], stepZ[i], stepTicks[i]);
            return new ClientboundMoveEntity(
                    entityId, new EntityDelta.Stepped(List.of(result)), yaw, pitch, onGround, true, hasRotation);
        }
        EntityDelta delta = hasPosition ? new EntityDelta.Linear(x, y, z) : EntityDelta.ZERO;
        return new ClientboundMoveEntity(entityId, delta, yaw, pitch, onGround, hasPosition, hasRotation);
    }
}
