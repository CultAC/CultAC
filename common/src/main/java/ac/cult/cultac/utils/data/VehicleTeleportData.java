package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.math.Vec3;
import lombok.Getter;

@Getter
public class VehicleTeleportData {
    private final int entityId;
    private final Vec3 position;
    private final float yaw;
    private final float pitch;
    private final Boolean onGround;
    private final Vec3 deltaMovement;

    public VehicleTeleportData(
            int entityId, Vec3 position, float yaw, float pitch, Boolean onGround, Vec3 deltaMovement) {
        this.entityId = entityId;
        this.position = position;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
        this.deltaMovement = deltaMovement;
    }
}
