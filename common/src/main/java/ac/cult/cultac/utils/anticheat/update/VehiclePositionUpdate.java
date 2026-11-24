package ac.cult.cultac.utils.anticheat.update;

import ac.cult.cultac.utils.math.Vec3;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** A LocalPlayer#tick move of a root this client controls; teleport answers and echoes never become one. */
@AllArgsConstructor
@Getter
public class VehiclePositionUpdate {
    private final Vec3 from, to;
    private final float xRot, yRot;
    private final boolean onGround;
    private final boolean hasOnGround;
    /** Whether the client sent this move in a MoveVehicle; otherwise the engine staged it for a tick that sent none. */
    private final boolean reported;
}
