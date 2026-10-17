package ac.cult.cultac.checks.impl.vehicle;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.DeadCheck;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.player.CultPlayer;

@CheckData(
        name = "VehicleB",
        stableKey = "cult.vehicle.spoofed_vehicle",
        description = "Claimed to be in a vehicle while not in a vehicle")
@DeadCheck(
        reason = DeadCheck.Reason.WIRE_UNTRIGGERABLE,
        detail = "The trigger is the pre-1.21.2 steer-vehicle packet; 1.21.2+ input is valid on foot.")
public class VehicleB extends Check implements CheckListener {
    public VehicleB(CultPlayer player) {
        super(player);
    }
}
