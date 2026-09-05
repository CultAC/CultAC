package ac.cult.cultac.checks.impl.vehicle;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.player.CultPlayer;

@CheckData(name = "VehicleB", stableKey = "cult.vehicle.spoofed_vehicle", description = "Claimed to be in a vehicle while not in a vehicle")
public class VehicleB extends Check implements CheckListener {
    public VehicleB(CultPlayer player) {
        super(player);
    }

    public boolean handleLegacySteerVehicle() {
        if (!player.inVehicle() && flag() && shouldModifyPackets()) {
            player.onPacketCancel();
            return true;
        }
        return false;
    }
}
