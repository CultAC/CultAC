package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.math.Vec3;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TeleportAcceptData {
    boolean isTeleport;
    boolean isInitialSpawnTeleport;
    boolean matchedTeleportPosition;
    SetBackData setback;
    TeleportData teleportData;
    VehicleTeleportData vehicleTeleportData;
    /**
     * The position a Java player's answer tells the server, in place of the coordinates the client reported: the exact
     * target it was moved to, or the seat a passenger stayed in. Null when the answer carries no position to forward.
     */
    Vec3 forwardedPosition;
    /** The yaw the ClientboundMoveVehicle this answers snaps the root to; NaN for an answer to another teleport. */
    float answerSnapYaw = Float.NaN;
    /** The root was farther than the snap distance from that ClientboundMoveVehicle when it was sent. */
    boolean answerSnaps;
}
