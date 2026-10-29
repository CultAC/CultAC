package ac.cult.cultac.checks.impl.vehicle;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.player.CultPlayer;

@CheckData(
        name = "VehicleC",
        stableKey = "cult.vehicle.vehicle_control",
        description = "Steered a pig or strider without its stick")
public class VehicleC extends Check implements CheckListener {
    public VehicleC(CultPlayer player) {
        super(player);
    }
}
