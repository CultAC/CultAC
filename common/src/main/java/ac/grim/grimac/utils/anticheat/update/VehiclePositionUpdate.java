package ac.grim.grimac.utils.anticheat.update;

import ac.grim.grimac.utils.data.TeleportAcceptData;
import net.minecraft.world.phys.Vec3;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@AllArgsConstructor
@Getter
@Setter
public class VehiclePositionUpdate {
    private final Vec3 from, to;
    private final float xRot, yRot;
    private final boolean onGround;
    private final boolean hasOnGround;
    private final TeleportAcceptData teleportAcceptData;
}
