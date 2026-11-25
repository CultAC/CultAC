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
    // The head is a rotate_head angle plus whole turns, to which float adds whole turns exactly.
    private boolean headTurns;
    private boolean headOTurns;
    private float xRotO;
    private boolean xRotOKnown;
    // The entity's yaw and pitch were set apart from its interpolation step since its last tick began.
    private boolean rotationPlaced;
    // AbstractHorse#tickRidden set the pitch from a controlling rider in the last tick.
    private boolean ridden;
    // AbstractHorse#tickRidden last set the pitch from a controlling rider other than the local player.
    private boolean riddenByOther;

    /** LivingEntity#recreateFromPacket: the head yaw, and Entity#absSnapTo's xRotO. */
    public void spawn(float headYaw, float pitch) {
        yHeadRot = headYaw;
        yHeadRotO = headYaw;
        lerpHeadSteps = 0;
        headKnown = true;
        headOKnown = true;
        headTurns = false;
        headOTurns = false;
        xRotO = CultMath.clamp(pitch, -90.0F, 90.0F) % 360.0F;
        xRotOKnown = true;
        rotationPlaced = false;
        ridden = false;
        riddenByOther = false;
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
     * controlling rider whose yaw is not known passes {@code controllerUnknown}, and one other than the local player
     * {@code controllerOther}. {@link #turnWithBody} follows once the entity has moved.
     */
    public void tick(
            float pitch, boolean pitchExact, float controllerYaw, boolean controllerUnknown, boolean controllerOther) {
        xRotO = pitch;
        xRotOKnown = pitchExact && !rotationPlaced && !ridden && !riddenByOther;
        rotationPlaced = false;
        ridden = controllerUnknown || !Float.isNaN(controllerYaw);
        if (ridden) riddenByOther = controllerOther;
        yHeadRotO = yHeadRot;
        headOKnown = headKnown;
        headOTurns = headTurns;
        if (lerpHeadSteps > 0) {
            if (headKnown) {
                yHeadRot = (float) CultMath.rotLerp(1.0 / lerpHeadSteps, (double) yHeadRot, lerpYHeadRot);
            } else {
                // The last step, the head plus Mth#wrapDegrees(angle - head) in double, is the angle plus whole turns.
                headTurns = lerpHeadSteps == 1;
                yHeadRot = (float) lerpYHeadRot;
            }
            lerpHeadSteps--;
        }
        if (controllerUnknown) {
            headKnown = false;
            headTurns = false;
        } else if (!Float.isNaN(controllerYaw)) {
            // Entity#setRot keeps the yaw modulo 360; Entity#setYRot drops one that is not finite.
            float yaw = controllerYaw % 360.0F;
            headKnown = Float.isFinite(yaw);
            headTurns = false;
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
            headTurns = false;
            if (Float.isNaN(bodyYaw)) {
                headKnown = false;
            } else if (headKnown) {
                yHeadRot =
                        bodyYaw - CultMath.clamp(CultMath.wrapDegrees(bodyYaw - yHeadRot), -maxHeadYRot, maxHeadYRot);
            }
        }
        if (headKnown) {
            // Whatever whole turns yHeadRotO's angle carries, the loops end on its one value within 180 of the head.
            while (yHeadRot - yHeadRotO < -180.0F) yHeadRotO -= 360.0F;
            while (yHeadRot - yHeadRotO >= 180.0F) yHeadRotO += 360.0F;
            headOKnown |= headOTurns;
            headOTurns = false;
        } else {
            // The range check adds whole turns, which keep yHeadRotO a rotate_head angle plus whole turns.
            headOKnown = false;
        }
        if (turnedAfter) {
            headKnown = false;
            headTurns = false;
        }
    }

    /** Whether the look AbstractHorse#addPassenger gives is known, with the entity's pitch {@code pitchExact}. */
    public boolean knowsRiderLook(boolean pitchExact) {
        return headKnown && headOKnown && xRotOKnown && !rotationPlaced && !ridden && pitchExact;
    }

    /**
     * The looks Entity#absSnapRotationTo takes from getViewYRot(0) and getViewXRot(0) as held here, into {@code look}:
     * yHeadRotO modulo 360, on either side of zero while yHeadRotO is a rotate_head angle plus whole turns, and xRotO.
     */
    public void riderLooks(HorseMountLook look, float pitch) {
        float yaw = headOKnown || headOTurns ? yHeadRotO % 360.0F : Float.NaN;
        float otherYaw = !headOTurns || yaw == 0.0F ? yaw : yaw < 0.0F ? yaw + 360.0F : yaw - 360.0F;
        look.hold(yaw, otherYaw, xRotOKnown && !rotationPlaced && !ridden ? riderPitch(pitch) : Float.NaN);
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
