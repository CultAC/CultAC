package ac.cult.cultac.network;

import ac.cult.cultac.protocol.codec.entity.EntityPositionSyncCodec;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import org.jetbrains.annotations.ApiStatus;

/**
 * One connection's values of a linear position sync its encoder read as {@link EntityPositionSyncCodec#SUNK}: the
 * components of the {@link ClientboundEntityPositionSync} the read would have returned, with its
 * {@link PositionPath.Linear} end position as {@link #x}, {@link #y} and {@link #z}. Held from the read until its
 * dispatch returns; one at a time. Model context only.
 */
@ApiStatus.Internal
public final class SunkPositionSync {
    boolean held;
    private int entityId;
    private double x, y, z;
    private float yaw, pitch;
    private boolean onGround;

    void hold(int entityId, double x, double y, double z, float yaw, float pitch, boolean onGround) {
        this.entityId = entityId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
        held = true;
    }

    /** Ends the hold the read began, once nothing dispatches its sync any more. */
    public void release() {
        held = false;
    }

    /** Whether a read sank a sync whose dispatch has not returned. */
    public boolean held() {
        return held;
    }

    private void requireHeld() {
        if (!held) throw new IllegalStateException("No sunk position sync is held");
    }

    public int entityId() {
        requireHeld();
        return entityId;
    }

    public double x() {
        requireHeld();
        return x;
    }

    public double y() {
        requireHeld();
        return y;
    }

    public double z() {
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

    /** The packet the read would have returned. */
    public ClientboundEntityPositionSync packet() {
        requireHeld();
        return new ClientboundEntityPositionSync(
                entityId, new PositionPath.Linear(new Vec3d(x, y, z)), yaw, pitch, onGround);
    }
}
