package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.utils.math.CultMath;

/**
 * An AbstractHorse's LivingEntity#yHeadRot, yHeadRotO and head lerp, and its Entity#xRotO, as a Java client holds them:
 * AbstractHorse#addPassenger turns each rider it gains to getViewYRot(0) and getViewXRot(0).
 */
public final class ClientHeadRotation {
    private float yHeadRot;
    private float yHeadRotO;
    private double lerpYHeadRot;
    private int lerpHeadSteps;
    private boolean headKnown;
    private boolean headOKnown;
    private float xRotO;
    private boolean xRotOKnown;
    // The entity's yaw and pitch were set apart from its interpolation step since its last tick began.
    private boolean rotationPlaced;
    // AbstractHorse#tickRidden set the pitch from a controlling rider in the last tick.
    private boolean ridden;

    /** LivingEntity#recreateFromPacket: the head yaw, and Entity#absSnapTo's xRotO. */
    public void spawn(float headYaw, float pitch) {
        yHeadRot = headYaw;
        yHeadRotO = headYaw;
        lerpHeadSteps = 0;
        headKnown = true;
        headOKnown = true;
        xRotO = CultMath.clamp(pitch, -90.0F, 90.0F) % 360.0F;
        xRotOKnown = true;
        rotationPlaced = false;
        ridden = false;
    }

    /** LivingEntity#lerpHeadTo from ClientPacketListener#handleRotateMob. */
    public void lerpTo(float headYaw) {
        lerpYHeadRot = headYaw;
        lerpHeadSteps = 3;
    }

    void rotationPlaced() {
        rotationPlaced = true;
    }

    /**
     * The entity's client tick up to its interpolation step. Entity#setOldPosAndRot takes xRotO from {@code pitch}, the
     * pitch before the tick's interpolation step. LivingEntity#baseTick takes yHeadRotO, LivingEntity#aiStep steps the
     * head lerp, and AbstractHorse#tickRidden sets the head to {@code controllerYaw}, NaN without a controlling rider; a
     * controlling rider whose yaw is not known passes {@code controllerUnknown}. {@link #turnWithBody} follows once the
     * entity has moved.
     */
    public void tick(float pitch, boolean pitchExact, float controllerYaw, boolean controllerUnknown) {
        xRotO = pitch;
        xRotOKnown = pitchExact && !rotationPlaced && !ridden;
        rotationPlaced = false;
        ridden = controllerUnknown || !Float.isNaN(controllerYaw);
        yHeadRotO = yHeadRot;
        headOKnown = headKnown;
        if (lerpHeadSteps > 0) {
            yHeadRot = (float) CultMath.rotLerp(1.0 / lerpHeadSteps, (double) yHeadRot, lerpYHeadRot);
            lerpHeadSteps--;
        }
        if (controllerUnknown) {
            headKnown = false;
        } else if (!Float.isNaN(controllerYaw)) {
            // Entity#setRot keeps the yaw modulo 360; Entity#setYRot drops one that is not finite.
            float yaw = controllerYaw % 360.0F;
            headKnown = Float.isFinite(yaw);
            if (headKnown) yHeadRot = yaw;
        }
    }

    /**
     * Mob#tickHeadTurn, then the rest of LivingEntity#tick. {@code turns} is a tick the entity moved horizontally, on
     * which BodyRotationControl#clientTick sets yBodyRot to the yaw {@code bodyYaw} and Mth#rotateIfNecessary brings
     * the head within {@code maxHeadYRot} degrees of it; {@code bodyYaw} is NaN when that yaw or the move is not known.
     * The range check then brings yHeadRotO within 180 degrees of the head. {@code turnedAfter} is a turn of the head
     * after that: Camel#tick's clampHeadRotationToBody or a vehicle's positionRider.
     */
    public void turnWithBody(boolean turns, float bodyYaw, float maxHeadYRot, boolean turnedAfter) {
        if (turns) {
            if (Float.isNaN(bodyYaw)) {
                headKnown = false;
            } else if (headKnown) {
                yHeadRot =
                        bodyYaw - CultMath.clamp(CultMath.wrapDegrees(bodyYaw - yHeadRot), -maxHeadYRot, maxHeadYRot);
            }
        }
        if (headKnown) {
            while (yHeadRot - yHeadRotO < -180.0F) yHeadRotO -= 360.0F;
            while (yHeadRot - yHeadRotO >= 180.0F) yHeadRotO += 360.0F;
        } else {
            headOKnown = false;
        }
        if (turnedAfter) headKnown = false;
    }

    /** Whether the look AbstractHorse#addPassenger gives is known, with the entity's pitch {@code pitchExact}. */
    public boolean knowsRiderLook(boolean pitchExact) {
        return headKnown && headOKnown && xRotOKnown && !rotationPlaced && !ridden && pitchExact;
    }

    /** Entity#absSnapRotationTo's yaw from LivingEntity#getViewYRot(0): Mth#rotLerp from yHeadRotO. */
    public float riderYaw() {
        return (yHeadRotO + 0.0F * CultMath.wrapDegrees(yHeadRot - yHeadRotO)) % 360.0F;
    }

    /** Entity#absSnapRotationTo's pitch from Entity#getViewXRot(0): Mth#lerp from xRotO to {@code pitch}. */
    public float riderPitch(float pitch) {
        return CultMath.clamp(xRotO + 0.0F * (pitch - xRotO), -90.0F, 90.0F) % 360.0F;
    }
}
