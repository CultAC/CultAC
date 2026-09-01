package ac.grim.grimac.checks.impl.vehicle;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;

@CheckData(name = "VehicleB", stableKey = "grim.vehicle.spoofed_vehicle", description = "Claimed to be in a vehicle while not in a vehicle")
public class VehicleB extends Check implements CheckListener {
    public VehicleB(GrimPlayer player) {
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
