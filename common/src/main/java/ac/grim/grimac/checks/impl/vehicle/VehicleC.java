package ac.grim.grimac.checks.impl.vehicle;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.player.GrimPlayer;

@CheckData(name = "VehicleC", stableKey = "grim.vehicle.vehicle_control", description = "Moved a vehicle in a way that did not match predicted vehicle control")
public class VehicleC extends Check implements CheckListener {
    public VehicleC(GrimPlayer player) {
        super(player);
    }
}
