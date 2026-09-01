package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.player.GrimPlayer;

/** Raw-NMS vehicle-authority validation with no Grim 2.0 shared equivalent. */
@CheckData(
        name = "BadPacketsVehicle",
        stableKey = "grim.badpackets.invalid_vehicle_movement",
        description = "Sent a vehicle movement packet without client movement authority",
        experimental = true
)
public final class BadPacketsVehicle extends Check implements CheckListener {
    public BadPacketsVehicle(GrimPlayer player) {
        super(player);
    }

    public void handleInvalidVehiclePacket(boolean fromClientTick) {
        flag("fromClientTick=" + fromClientTick);
    }
}
