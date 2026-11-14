package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.utils.data.RidingJumpCharge.Jumpable;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** RidingJumpCharge against LocalPlayer#aiStep's riding-jump block, tick by tick. */
class RidingJumpChargeTest {
    /** LocalPlayer#aiStep's jumpRidingTicks, jumpRidingScale and the keys of the last tick. */
    private static final class VanillaRider {
        int jumpRidingTicks;
        float jumpRidingScale;
        boolean lastKey;

        /** One aiStep; the START_RIDING_JUMP data it sends, or null. */
        Integer aiStep(boolean jumpable, boolean key) {
            boolean wasJumping = lastKey;
            lastKey = key;
            if (!jumpable) {
                jumpRidingScale = 0.0F;
                return null;
            }
            if (jumpRidingTicks < 0) {
                jumpRidingTicks++;
                if (jumpRidingTicks == 0) jumpRidingScale = 0.0F;
            }
            if (wasJumping && !key) {
                jumpRidingTicks = -10;
                return (int) Math.floor(jumpRidingScale * 100.0F);
            } else if (!wasJumping && key) {
                jumpRidingTicks = 0;
                jumpRidingScale = 0.0F;
            } else if (wasJumping) {
                jumpRidingTicks++;
                if (jumpRidingTicks < 10) {
                    jumpRidingScale = jumpRidingTicks * 0.1F;
                } else {
                    jumpRidingScale = 0.8F + 2.0F / (jumpRidingTicks - 9) * 0.1F;
                }
            }
            return null;
        }
    }

    /** Runs both over {@code ticks} of keys and jumpable vehicles, each release judged as the engine judges it. */
    private static int compare(boolean[] keys, boolean[] jumpable) {
        VanillaRider client = new VanillaRider();
        RidingJumpCharge charge = new RidingJumpCharge();
        int releases = 0;
        for (int tick = 0; tick < keys.length; tick++) {
            Jumpable vehicle = jumpable[tick] ? Jumpable.YES : Jumpable.NO;
            Integer sent = client.aiStep(jumpable[tick], keys[tick]);
            if (sent != null) {
                Integer judged = charge.releaseData(vehicle);
                assertTrue(judged == null || judged.equals(sent), "tick " + tick + ": " + judged + " for " + sent);
                if (judged != null) releases++;
            }
            charge.onClientTick(vehicle, keys[tick]);
        }
        return releases;
    }

    @Test
    void everyJudgedReleaseCarriesTheClientsCharge() {
        Random random = new Random(7);
        int judged = 0;
        for (int run = 0; run < 2000; run++) {
            int length = 60;
            boolean[] keys = new boolean[length];
            boolean[] jumpable = new boolean[length];
            boolean key = false;
            for (int tick = 0; tick < length; tick++) {
                if (random.nextInt(run % 2 == 0 ? 4 : 12) == 0) key = !key;
                keys[tick] = key;
                jumpable[tick] = random.nextInt(run % 3 == 0 ? 3 : 20) != 0;
            }
            judged += compare(keys, jumpable);
        }
        assertTrue(judged > 1000, "judged releases " + judged);
    }

    /**
     * A press on a tick without a jumpable vehicle keeps the release's cooldown, which a held key counts up twice a tick;
     * a release whose tick counts it from -1 to 0 sends a cleared scale.
     */
    @Test
    void aReleaseThatEndsTheCooldownSendsNoCharge() {
        boolean[] keys = {false, true, true, false, false, true, true, true, true, true, false, true, false};
        boolean[] jumpable = {true, true, true, true, true, false, true, true, true, true, true, true, true};
        assertEquals(3, compare(keys, jumpable));
    }
}
