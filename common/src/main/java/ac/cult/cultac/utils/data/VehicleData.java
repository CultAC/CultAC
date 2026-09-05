package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.enums.BoatEntityStatus;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ConcurrentLinkedQueue;

public class VehicleData {
    public boolean boatUnderwater = false;
    public double lastYd;
    public double midTickY;
    public float landFriction;
    public BoatEntityStatus status;
    public BoatEntityStatus oldStatus;
    public double waterLevel;
    public float deltaRotation;
    public float nextVehicleHorizontal = 0f;
    public float nextVehicleForward = 0f;
    public float vehicleHorizontal = 0f;
    public float vehicleForward = 0f;
    public boolean lastDummy = false;
    public boolean wasVehicleSwitch = false;
    public float playerPitch = 0f;
    public float playerYaw = 0f;
    public final ConcurrentLinkedQueue<IntToObjectPair<Vec3>> vehicleTeleports = new ConcurrentLinkedQueue<>();
    public SprintingState camelSprintingState = SprintingState.STOPPED;
}
