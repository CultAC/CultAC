package ac.cult.cultac.utils.anticheat.update;

import ac.cult.cultac.checks.impl.aim.processor.AimProcessor;
import ac.cult.cultac.utils.data.HeadRotation;
import lombok.Getter;
import lombok.Setter;

@Getter
public final class RotationUpdate {
    private HeadRotation from, to;

    @Setter
    private AimProcessor processor;

    private float deltaYRot, deltaXRot;
    private boolean isCinematic;
    // The rotation a teleport answer set, not one the player turned to.
    private final boolean teleport;
    private double sensitivityX, sensitivityY;

    public RotationUpdate(HeadRotation from, HeadRotation to, float deltaXRot, float deltaYRot) {
        this(from, to, deltaXRot, deltaYRot, false);
    }

    public RotationUpdate(HeadRotation from, HeadRotation to, float deltaXRot, float deltaYRot, boolean teleport) {
        this.from = from;
        this.teleport = teleport;
        this.to = to;
        // Two finite wire rotations can still overflow when subtracted as floats.
        this.deltaXRot = Float.isFinite(deltaXRot) ? deltaXRot : finiteDifference(to.yaw(), from.yaw());
        this.deltaYRot = Float.isFinite(deltaYRot) ? deltaYRot : finiteDifference(to.pitch(), from.pitch());
    }

    public static float finiteDifference(float end, float start) {
        float difference = end - start;
        return Float.isFinite(difference) ? difference : finiteFloat((double) end - start);
    }

    public static float finiteFloat(double value) {
        if (Double.isNaN(value)) return 0;
        if (value > Float.MAX_VALUE) return Float.MAX_VALUE;
        if (value < -Float.MAX_VALUE) return -Float.MAX_VALUE;
        return (float) value;
    }

    public float getDeltaXRotABS() {
        return Math.abs(deltaXRot);
    }

    public float getDeltaYRotABS() {
        return Math.abs(deltaYRot);
    }
}
