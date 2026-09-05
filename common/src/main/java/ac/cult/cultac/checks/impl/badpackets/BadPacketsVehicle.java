package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.player.CultPlayer;

/** Raw-NMS vehicle-authority validation with no Cult 2.0 shared equivalent. */
@CheckData(
        name = "BadPacketsVehicle",
        stableKey = "cult.badpackets.invalid_vehicle_movement",
        description = "Sent a vehicle movement packet without client movement authority",
        experimental = true
)
public final class BadPacketsVehicle extends Check implements CheckListener {
    public BadPacketsVehicle(CultPlayer player) {
        super(player);
    }

    public void handleInvalidVehiclePacket(boolean fromClientTick) {
        flag("fromClientTick=" + fromClientTick);
    }
}
