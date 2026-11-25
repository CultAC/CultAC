package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.math.Vec3;
import lombok.Getter;

@Getter
public class VehicleTeleportData {
    public enum Application {
        ENTITY_TELEPORT,
        MOVE_VEHICLE_SNAP,
        MOVE_VEHICLE_NO_SNAP
    }

    private final Application application;
    private final int entityId;
    private final Vec3 position;
    private final float yaw;
    private final float pitch;
    private final Boolean onGround;
    private final Vec3 deltaMovement;

    public VehicleTeleportData(
            int entityId, Vec3 position, float yaw, float pitch, Boolean onGround, Vec3 deltaMovement) {
        this(Application.ENTITY_TELEPORT, entityId, position, yaw, pitch, onGround, deltaMovement);
    }

    private VehicleTeleportData(
            Application application,
            int entityId,
            Vec3 position,
            float yaw,
            float pitch,
            Boolean onGround,
            Vec3 deltaMovement) {
        this.application = application;
        this.entityId = entityId;
        this.position = position;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
        this.deltaMovement = deltaMovement;
    }

    /** ClientPacketListener#handleMoveVehicle changes neither velocity nor ground state. */
    public static VehicleTeleportData correction(int entityId, Vec3 position, float yaw, float pitch, boolean snaps) {
        return new VehicleTeleportData(
                snaps ? Application.MOVE_VEHICLE_SNAP : Application.MOVE_VEHICLE_NO_SNAP,
                entityId,
                position,
                yaw,
                pitch,
                null,
                null);
    }
}
