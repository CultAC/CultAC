package ac.grim.grimac.utils.data;

import ac.grim.grimac.checks.impl.prediction.PredictionSetbackState;
import net.minecraft.world.phys.Vec3;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class SetBackData {
    TeleportData teleportData;
    float xRot, yRot;
    Vec3 velocity;
    boolean vehicle;
    boolean expectedOnGround;
    PredictionSetbackState profileState;
    boolean isComplete = false;
    // TODO: Rethink when we block movements for teleports, perhaps after 10 ticks or 5 blocks?
    boolean isPlugin = false;
    int ticksComplete = 0;

    public SetBackData(TeleportData teleportData, float xRot, float yRot, Vec3 velocity, boolean vehicle, boolean isPlugin) {
        this(teleportData, xRot, yRot, velocity, vehicle, isPlugin, false);
    }

    public SetBackData(TeleportData teleportData, float xRot, float yRot, Vec3 velocity, boolean vehicle, boolean isPlugin, boolean expectedOnGround) {
        this(teleportData, xRot, yRot, velocity, vehicle, isPlugin, expectedOnGround, null);
    }

    public SetBackData(TeleportData teleportData, float xRot, float yRot, Vec3 velocity, boolean vehicle,
                       boolean isPlugin, boolean expectedOnGround, PredictionSetbackState profileState) {
        this.teleportData = teleportData;
        this.xRot = xRot;
        this.yRot = yRot;
        this.velocity = velocity;
        this.vehicle = vehicle;
        this.isPlugin = isPlugin;
        this.expectedOnGround = expectedOnGround;
        this.profileState = profileState;
    }

    public void tick() {
        if (isComplete) ticksComplete++;
    }
}
