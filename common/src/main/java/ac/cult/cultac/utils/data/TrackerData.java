package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.math.Vec3;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.jetbrains.annotations.Nullable;

@Data
@AllArgsConstructor
public class TrackerData {

    public TrackerData(double x, double y, double z, float xRot, float yRot, int entityType, int lastTransactionHung) {
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
    int entityType;
    int lastTransactionHung;
    boolean onGround;
    int legacyPointEightMountedUpon;
    float health;

    // The transform the client's packet handler last wrote; it holds only in the client tick it was written in.
    double handlerX, handlerY, handlerZ;
    float handlerYRot, handlerXRot;
    long handlerTransformTick = -1;
    // The delta movement the client's packet handler last set, until the proof that applies it clears it.
    @Nullable
    Vec3 handlerDeltaMovement;

    int handlerDeltaSequence;

    public void setHandlerTransform(double x, double y, double z, float yRot, float xRot, long clientTick) {
        handlerX = x;
        handlerY = y;
        handlerZ = z;
        handlerYRot = yRot;
        handlerXRot = xRot;
        handlerTransformTick = clientTick;
    }

    public boolean hasHandlerTransformIn(long clientTick) {
        return handlerTransformTick == clientTick;
    }

    public void setHandlerDeltaMovement(Vec3 movement, int sequence) {
        handlerDeltaMovement = movement;
        handlerDeltaSequence = sequence;
    }

    /** Clears the handler delta movement once the proof of the packet that set it, {@code sequence}, has applied it. */
    public void handlerDeltaMovementApplied(int sequence) {
        if (handlerDeltaSequence == sequence) handlerDeltaMovement = null;
    }
}
