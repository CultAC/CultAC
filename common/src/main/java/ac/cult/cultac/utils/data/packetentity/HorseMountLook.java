package ac.cult.cultac.utils.data.packetentity;

/**
 * The looks AbstractHorse#addPassenger may have turned the local player to on a set_passengers, as
 * {@link ClientHeadRotation#riderLooks} gives them, until the player's next movement packet: LocalPlayer#sendChanges
 * reports the look in a passenger's Rot.
 */
public final class HorseMountLook {
    private boolean held;
    private float yaw;
    private float otherYaw;
    private float pitch;

    void hold(float yaw, float otherYaw, float pitch) {
        held = true;
        this.yaw = yaw;
        this.otherYaw = otherYaw;
        this.pitch = pitch;
    }

    public void clear() {
        held = false;
    }

    /** Whether a movement packet reporting {@code yaw} and {@code pitch} reports one of these looks. */
    public boolean reports(float yaw, float pitch) {
        if (!held) return false;
        // Entity#absSnapRotationTo leaves the yaw within a turn of zero and the pitch within 90 degrees of level.
        boolean yawGiven = Float.isNaN(this.yaw) ? yaw > -360.0F && yaw < 360.0F : yaw == this.yaw || yaw == otherYaw;
        boolean pitchGiven = Float.isNaN(this.pitch) ? pitch >= -90.0F && pitch <= 90.0F : pitch == this.pitch;
        return yawGiven && pitchGiven;
    }
}
