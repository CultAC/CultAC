package ac.cult.cultac.utils.data;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionSetbackState;
import ac.cult.cultac.utils.math.Vec3;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class SetBackData {
    TeleportData teleportData;
    float xRot, yRot;
    // The rotation ClientboundMoveVehiclePacket#fromEntity gives a vehicle setback; NaN for the player's.
    float vehicleYaw = Float.NaN, vehiclePitch = Float.NaN;
    Vec3 velocity;
    boolean vehicle;
    boolean expectedOnGround;
    // The onGround a Java horse setback leaves the horse with at its position; null keeps the carried one.
    DesyncStatus horseOnGround;
    // The onGround a Java player correction leaves the player with: its tick's own landing, else the safe ground.
    // Null keeps the carried one.
    DesyncStatus groundAfter;
    PredictionSetbackState profileState;
    boolean isComplete = false;
    // TODO: Rethink when we block movements for teleports, perhaps after 10 ticks or 5 blocks?
    boolean isPlugin = false;

    public SetBackData(
            TeleportData teleportData, float xRot, float yRot, Vec3 velocity, boolean vehicle, boolean isPlugin) {
        this(teleportData, xRot, yRot, velocity, vehicle, isPlugin, false);
    }

    public SetBackData(
            TeleportData teleportData,
            float xRot,
            float yRot,
            Vec3 velocity,
            boolean vehicle,
            boolean isPlugin,
            boolean expectedOnGround) {
        this(teleportData, xRot, yRot, velocity, vehicle, isPlugin, expectedOnGround, null);
    }

    public SetBackData(
            TeleportData teleportData,
            float xRot,
            float yRot,
            Vec3 velocity,
            boolean vehicle,
            boolean isPlugin,
            boolean expectedOnGround,
            PredictionSetbackState profileState) {
        this.teleportData = teleportData;
        this.xRot = xRot;
        this.yRot = yRot;
        this.velocity = velocity;
        this.vehicle = vehicle;
        this.isPlugin = isPlugin;
        this.expectedOnGround = expectedOnGround;
        this.profileState = profileState;
    }
}
