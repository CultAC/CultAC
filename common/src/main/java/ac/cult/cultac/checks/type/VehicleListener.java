package ac.cult.cultac.checks.type;

import ac.cult.cultac.utils.anticheat.update.VehiclePositionUpdate;

public interface VehicleListener extends CheckListener {

    void process(final VehiclePositionUpdate vehicleUpdate);
}
