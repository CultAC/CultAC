package ac.cult.cultac.network;

import ac.cult.cultac.protocol.codec.entity.MoveEntityCodec;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import org.jetbrains.annotations.ApiStatus;

/**
 * One connection's values of a linear move its encoder read as {@link MoveEntityCodec#SUNK}: the components of the
 * {@link ClientboundMoveEntity} the read would have returned, with its {@link EntityDelta.Linear} delta as
 * {@link #x}, {@link #y} and {@link #z}. Held from the read until its dispatch returns; one at a time. Model context
 * only.
 */
@ApiStatus.Internal
public final class SunkMove {
    boolean held;
    private int entityId;
    private short x, y, z;
    private float yaw, pitch;
    private boolean onGround, hasPosition, hasRotation;

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

    /** The packet the read would have returned. */
    public ClientboundMoveEntity packet() {
        requireHeld();
        EntityDelta delta = hasPosition ? new EntityDelta.Linear(x, y, z) : EntityDelta.ZERO;
        return new ClientboundMoveEntity(entityId, delta, yaw, pitch, onGround, hasPosition, hasRotation);
    }
}
