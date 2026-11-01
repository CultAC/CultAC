package ac.cult.cultac.utils.anticheat.update;

import ac.cult.cultac.utils.data.TeleportAcceptData;
import ac.cult.cultac.utils.math.Vec3;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class VehiclePositionUpdate {
    private final Vec3 from, to;
    private final float xRot, yRot;
    private final boolean onGround;
    private final boolean hasOnGround;
    private final TeleportAcceptData teleportAcceptData;
}
