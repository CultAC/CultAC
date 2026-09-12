package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.network.protocol.ClientVersion;

/** Client ItemBasedSteering state, advanced by a tick-produced vehicle packet. */
public final class RideableBoostState {
    private boolean boosting;
    private int elapsed;
    private int duration;
    private boolean hasTicked;
    private int lastClientTick;

    public void onSynced(int duration) {
        this.duration = duration;
        elapsed = 0;
        boosting = true;
    }

    public void tick(int clientTick) {
        if (hasTicked && lastClientTick == clientTick) return;
        hasTicked = true;
        lastClientTick = clientTick;
        // Vanilla compares the OLD time, then increments even on the expiry tick.
        if (boosting && elapsed++ > duration) boosting = false;
    }

    public float factor(ClientVersion version) {
        // Pig/Strider#tickRidden has already advanced before getRiddenSpeed.
        return boosting
                ? 1.0F + 1.15F * VanillaMath.sin(version, (float) elapsed / duration * (float) Math.PI)
                : 1.0F;
    }
}
