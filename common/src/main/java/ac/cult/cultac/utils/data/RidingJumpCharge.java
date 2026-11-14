package ac.cult.cultac.utils.data;

import org.jetbrains.annotations.Nullable;

/**
 * LocalPlayer#aiStep's riding-jump charge, jumpRidingTicks and jumpRidingScale, followed from the jump key of each
 * client tick. The charge is known only from a press on a tick whose vehicle is surely jumpable; until then only whether
 * a release can be sent at all is judged.
 */
public final class RidingJumpCharge {
    /** Whether LocalPlayer#jumpableVehicle held a vehicle whose getJumpCooldown was 0 in a tick. */
    public enum Jumpable {
        YES,
        NO,
        UNKNOWN
    }

    /** A release this tick cannot send START_RIDING_JUMP. */
    public static final int NO_RELEASE = Integer.MIN_VALUE;

    private boolean known;
    private int ticks;
    private float scale;
    private boolean keyKnown;
    private boolean lastJump;
    private boolean released;

    public void forget() {
        known = false;
        keyKnown = false;
        released = false;
    }

    /**
     * The data a START_RIDING_JUMP sent this tick carries: Mth.floor(getJumpRidingScale() * 100.0F) of the charge the
     * last tick left, sent only when the jump key held in the last tick is released on a jumpable tick. NO_RELEASE when
     * no release can send it, null when the charge or the vehicle is not known.
     */
    @Nullable
    public Integer releaseData(Jumpable jumpable) {
        if (!keyKnown || jumpable == Jumpable.UNKNOWN) return null;
        if (jumpable == Jumpable.NO || !lastJump) {
            released = true;
            return NO_RELEASE;
        }
        released = true;
        if (!known) return null;
        // The release tick first counts a cooldown of -1 up to 0, which clears the scale.
        return ticks == -1 ? 0 : (int) Math.floor(scale * 100.0F);
    }

    /** LocalPlayer#aiStep for a tick whose jump key is {@code jump}. */
    public void onClientTick(Jumpable jumpable, boolean jump) {
        boolean wasJumping = lastJump;
        boolean releasedThisTick = released;
        // LocalPlayer#aiStep releases only with the jump key up.
        lastJump = jump && !releasedThisTick;
        keyKnown = true;
        released = false;
        if (jumpable == Jumpable.UNKNOWN) {
            known = false;
            return;
        }
        if (!known) {
            if (jumpable == Jumpable.YES && !wasJumping && jump) {
                known = true;
                ticks = 0;
                scale = 0.0F;
            }
            return;
        }
        if (jumpable == Jumpable.NO) {
            scale = 0.0F;
        } else {
            if (ticks < 0) {
                ticks++;
                if (ticks == 0) scale = 0.0F;
            }
            if (wasJumping && !jump) {
                ticks = -10;
            } else if (!wasJumping && jump) {
                ticks = 0;
                scale = 0.0F;
            } else if (wasJumping) {
                ticks++;
                if (ticks < 10) {
                    scale = ticks * 0.1F;
                } else {
                    scale = 0.8F + 2.0F / (ticks - 9) * 0.1F;
                }
            }
        }
        // The release ends the charge.
        if (releasedThisTick) ticks = -10;
    }
}
