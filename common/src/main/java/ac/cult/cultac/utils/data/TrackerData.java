package ac.cult.cultac.utils.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import net.minecraft.world.entity.EntityType;

@Data
@AllArgsConstructor
public class TrackerData {

    public TrackerData(double x, double y, double z, float xRot, float yRot, EntityType entityType, int lastTransactionHung) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.codecBaseX = x;
        this.codecBaseY = y;
        this.codecBaseZ = z;
        this.xRot = xRot;
        this.yRot = yRot;
        this.entityType = entityType;
        this.lastTransactionHung = lastTransactionHung;
    }

    double x, y, z;
    double codecBaseX, codecBaseY, codecBaseZ;
    float xRot, yRot;
    EntityType entityType;
    int lastTransactionHung;
    boolean onGround;
    int legacyPointEightMountedUpon;
    float health;
}
